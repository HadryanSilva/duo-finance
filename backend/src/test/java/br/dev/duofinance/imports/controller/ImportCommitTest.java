package br.dev.duofinance.imports.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.imports.service.DescriptionKeys;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class ImportCommitTest {

    private static final String PREVIEW = "/api/v1/imports/revolut/preview";
    private static final String COMMIT = "/api/v1/imports/revolut/commit";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    @Autowired
    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    private static byte[] sample() throws IOException {
        return Files.readAllBytes(Path.of("src/test/resources/imports/revolut-sample.csv"));
    }

    private MvcTestResult post(
            String uri, TestSession s, byte[] file, String bankAccountId, String mapping, String decisions) {
        var builder = mvc.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .multipart()
                .file(new MockMultipartFile("file", "statement.csv", "text/csv", file))
                .file(new MockMultipartFile("bankAccountId", "", "text/plain", bankAccountId.getBytes()));
        if (mapping != null) {
            builder = builder.file(new MockMultipartFile("productMapping", "", "application/json", mapping.getBytes()));
        }
        if (decisions != null) {
            builder = builder.file(new MockMultipartFile("decisions", "", "application/json", decisions.getBytes()));
        }
        return builder.exchange();
    }

    private JsonNode body(MvcTestResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode previewRows(TestSession s, UUID bank, String mapping) throws Exception {
        return body(post(PREVIEW, s, sample(), bank.toString(), mapping, null)).path("rows");
    }

    private static JsonNode row(JsonNode rows, String description) {
        for (JsonNode r : rows) {
            if (description.equals(r.path("description").asString())) {
                return r;
            }
        }
        throw new AssertionError("no row " + description);
    }

    private static List<JsonNode> rowsNamed(JsonNode rows, String description) {
        List<JsonNode> out = new ArrayList<>();
        for (JsonNode r : rows) {
            if (description.equals(r.path("description").asString())) {
                out.add(r);
            }
        }
        return out;
    }

    private static Map<String, Object> decision(JsonNode row, boolean include, UUID category) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("rowId", row.path("rowId").asString());
        m.put("include", include);
        m.put("categoryId", category);
        return m;
    }

    private String decisions(List<Map<String, Object>> list) {
        return json.writeValueAsString(list);
    }

    private MvcTestResult commit(TestSession s, UUID bank, List<Map<String, Object>> list) throws IOException {
        return post(COMMIT, s, sample(), bank.toString(), null, decisions(list));
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    /** Includes every previewed row; income and expense rows get the matching category. */
    private List<Map<String, Object>> everything(JsonNode rows, UUID expense, UUID income) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonNode r : rows) {
            String kind = r.path("kind").asString();
            UUID category = kind.equals("INCOME") ? income : kind.equals("EXPENSE") ? expense : null;
            list.add(decision(r, true, category));
        }
        return list;
    }

    @Test
    void commit_createsBatchTransactionsAndMemory() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cafes = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        JsonNode rows = previewRows(s, bank, null);
        JsonNode cafe = row(rows, "Cafe Aurora");
        JsonNode mercado = row(rows, "Mercado Lua, Lda");

        var result = commit(s, bank, List.of(decision(cafe, true, cafes), decision(mercado, true, cafes)));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        JsonNode response = body(result);
        assertThat(response.path("created").asInt()).isEqualTo(2);
        assertThat(response.path("skippedDuplicates").asInt()).isZero();
        UUID batchId = UUID.fromString(response.path("batchId").asString());
        assertThat(count("SELECT row_count FROM import_batches WHERE id = ?", batchId))
                .isEqualTo(2);
        assertThat(count("SELECT count(*) FROM transactions WHERE import_batch_id = ?", batchId))
                .isEqualTo(2);
        var stored = jdbc.queryForMap(
                "SELECT import_fingerprint, kind, amount_cents, category_id, bank_account_id, created_by"
                        + " FROM transactions WHERE description = 'Cafe Aurora'");
        assertThat(stored.get("import_fingerprint")).isEqualTo(cafe.path("rowId").asString());
        assertThat(stored.get("kind")).isEqualTo("EXPENSE");
        assertThat(stored.get("category_id")).isEqualTo(cafes);
        assertThat(stored.get("bank_account_id")).isEqualTo(bank);
        assertThat(stored.get("created_by")).isEqualTo(s.userId());
        assertThat(count(
                        "SELECT count(*) FROM category_memory WHERE account_id = ? AND category_id = ? AND kind = 'EXPENSE'",
                        s.accountId(),
                        cafes))
                .isEqualTo(2);
    }

    @Test
    void commit_reimportSameFile_createsNone() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cafes = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        JsonNode cafe = row(previewRows(s, bank, null), "Cafe Aurora");
        assertThat(commit(s, bank, List.of(decision(cafe, true, cafes)))).hasStatus(HttpStatus.CREATED);
        long batches = count("SELECT count(*) FROM import_batches");

        // the same decision is now rejected because the row is ALREADY_IMPORTED; a pure re-import excludes it
        var again = commit(s, bank, List.of(decision(cafe, false, null)));

        assertThat(again).hasStatus(HttpStatus.CREATED);
        JsonNode response = body(again);
        assertThat(response.path("created").asInt()).isZero();
        assertThat(response.path("batchId").isNull()).isTrue();
        assertThat(count("SELECT count(*) FROM import_batches")).isEqualTo(batches);
        assertThat(count("SELECT count(*) FROM transactions WHERE import_fingerprint IS NOT NULL"))
                .isEqualTo(1);
    }

    @Test
    void commit_identicalRowsBothImportedAndReimportCreatesNone() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID leisure = fixtures.category(s, "Leisure", CategoryKind.EXPENSE);
        JsonNode rows = previewRows(s, bank, null);
        List<JsonNode> cinemas = rowsNamed(rows, "Cinema Plaza");
        assertThat(cinemas).hasSize(2);
        assertThat(cinemas.get(0).path("rowId").asString())
                .isNotEqualTo(cinemas.get(1).path("rowId").asString());

        var first = commit(s, bank, cinemas.stream().map(r -> decision(r, true, leisure)).toList());

        assertThat(body(first).path("created").asInt()).isEqualTo(2);
        assertThat(count("SELECT count(*) FROM transactions WHERE description = 'Cinema Plaza'"))
                .isEqualTo(2);
        JsonNode after = previewRows(s, bank, null);
        assertThat(rowsNamed(after, "Cinema Plaza"))
                .allMatch(r -> r.path("status").asString().equals("ALREADY_IMPORTED"));
        var again = commit(
                s, bank, rowsNamed(after, "Cinema Plaza").stream().map(r -> decision(r, false, null)).toList());
        assertThat(body(again).path("created").asInt()).isZero();
        assertThat(count("SELECT count(*) FROM transactions WHERE description = 'Cinema Plaza'"))
                .isEqualTo(2);
    }

    @Test
    void commit_twoConcurrentCommits_noDuplicates() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID expense = fixtures.category(s, "Expenses", CategoryKind.EXPENSE);
        UUID income = fixtures.category(s, "Earnings", CategoryKind.INCOME);
        JsonNode rows = previewRows(s, bank, null);
        String payload = decisions(everything(rows, expense, income));
        int included = rows.size();
        byte[] file = sample();

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Future<MvcTestResult>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return post(COMMIT, s, file, bank.toString(), null, payload);
            }));
        }
        start.countDown();
        int winners = 0;
        for (Future<MvcTestResult> f : futures) {
            MvcTestResult result = f.get();
            if (result.getResponse().getStatus() == HttpStatus.CREATED.value()) {
                winners++;
            } else {
                // the other request planned after the first committed: correct, not a duplicate write
                assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
                JsonNode errors = body(result).path("errors");
                assertThat(errors.size()).isGreaterThan(0);
                for (JsonNode e : errors) {
                    assertThat(e.path("code").asString()).isEqualTo("ROW_ALREADY_IMPORTED");
                }
            }
        }
        pool.shutdown();
        assertThat(winners).isGreaterThanOrEqualTo(1);

        assertThat(count("SELECT count(*) FROM transactions WHERE import_fingerprint IS NOT NULL"))
                .isEqualTo(included);
        assertThat(count("SELECT coalesce(sum(row_count), 0) FROM import_batches"))
                .isEqualTo(included);
        assertThat(count("SELECT count(*) FROM import_batches WHERE row_count = 0"))
                .isZero();
    }

    @Autowired
    br.dev.duofinance.imports.service.ImportedTransactionWriter writer;

    @Autowired
    br.dev.duofinance.imports.repository.ImportBatchRepository batchRepository;

    @Autowired
    org.springframework.transaction.PlatformTransactionManager txManager;

    @Test
    void writer_insertSameFingerprintTwice_returnsIdThenNull() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cat = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        var row = new br.dev.duofinance.imports.service.PlannedRow(
                "fp-writer-test",
                java.time.LocalDate.of(2026, 10, 4),
                br.dev.duofinance.transaction.model.TransactionKind.EXPENSE,
                1250,
                "Cafe Aurora",
                "cafe aurora",
                bank,
                null,
                null,
                br.dev.duofinance.imports.service.RowStatus.NEW,
                null,
                java.time.LocalDateTime.of(2026, 10, 3, 12, 4, 51),
                2);
        java.time.Instant now = java.time.Instant.parse("2026-11-10T10:00:00Z");

        UUID[] results = new org.springframework.transaction.support.TransactionTemplate(txManager).execute(status -> {
            var batch = batchRepository.saveAndFlush(new br.dev.duofinance.imports.model.ImportBatch(
                    s.accountId(), br.dev.duofinance.imports.model.ImportSource.REVOLUT, bank, s.userId(), 0, now));
            return new UUID[] {
                writer.insert(s.accountId(), s.userId(), batch.getId(), row, cat, now),
                writer.insert(s.accountId(), s.userId(), batch.getId(), row, cat, now)
            };
        });

        assertThat(results[0]).isNotNull();
        assertThat(results[1]).isNull();
        assertThat(count("SELECT count(*) FROM transactions WHERE id = ?", results[0])).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transactions WHERE import_fingerprint = 'fp-writer-test'"))
                .isEqualTo(1);
    }

    @Test
    void commit_possibleDuplicateIncludedOnlyWhenChosen() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cat = fixtures.category(s, "Market", CategoryKind.EXPENSE);
        fixtures.transaction(
                s,
                Map.of(
                        "kind", "EXPENSE", "amountCents", 4530, "date", "2026-10-06",
                        "categoryId", cat, "bankAccountId", bank));
        JsonNode rows = previewRows(s, bank, null);
        JsonNode mercado = row(rows, "Mercado Lua, Lda");
        assertThat(mercado.path("status").asString()).isEqualTo("POSSIBLE_DUPLICATE");
        JsonNode cafe = row(rows, "Cafe Aurora");

        var notChosen = commit(s, bank, List.of(decision(cafe, true, cat)));
        assertThat(body(notChosen).path("created").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transactions WHERE description = 'Mercado Lua, Lda'"))
                .isZero();

        var chosen = commit(s, bank, List.of(decision(mercado, true, cat)));
        assertThat(body(chosen).path("created").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transactions WHERE description = 'Mercado Lua, Lda'"))
                .isEqualTo(1);
    }

    @Test
    void commit_valuesComeFromFile() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cat = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        JsonNode cafe = row(previewRows(s, bank, null), "Cafe Aurora");
        Map<String, Object> tampered = decision(cafe, true, cat);
        tampered.put("amountCents", 1);
        tampered.put("kind", "INCOME");
        tampered.put("date", "2020-01-01");

        var result = commit(s, bank, List.of(tampered));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        var stored = jdbc.queryForMap("SELECT amount_cents, kind, date::text AS date FROM transactions"
                + " WHERE description = 'Cafe Aurora'");
        assertThat(stored.get("amount_cents")).isEqualTo(1250L);
        assertThat(stored.get("kind")).isEqualTo("EXPENSE");
        assertThat(stored.get("date")).isEqualTo("2026-10-04");
    }

    @Test
    void commit_rowWithoutDecision_notImported() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cat = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        JsonNode cafe = row(previewRows(s, bank, null), "Cafe Aurora");

        var result = commit(s, bank, List.of(decision(cafe, true, cat)));

        assertThat(body(result).path("created").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transactions")).isEqualTo(1);
    }

    @Test
    void commit_transferIncludedWithoutCategory_createsTransfer() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID savings = fixtures.bankAccount(s, "Savings", 0);
        String mapping = "{\"Savings\":\"" + savings + "\"}";
        JsonNode transfer = row(previewRows(s, bank, mapping), "To Savings");
        assertThat(transfer.path("kind").asString()).isEqualTo("TRANSFER");

        var result = post(COMMIT, s, sample(), bank.toString(), mapping, decisions(List.of(decision(transfer, true, null))));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        var stored = jdbc.queryForMap(
                "SELECT bank_account_id, destination_bank_account_id, category_id FROM transactions WHERE kind = 'TRANSFER'");
        assertThat(stored.get("bank_account_id")).isEqualTo(bank);
        assertThat(stored.get("destination_bank_account_id")).isEqualTo(savings);
        assertThat(stored.get("category_id")).isNull();
        assertThat(count("SELECT count(*) FROM category_memory")).isZero();
    }

    @Test
    void commit_reimportMappedTransferSameMapping_createsNothing() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID savings = fixtures.bankAccount(s, "Savings", 0);
        UUID expense = fixtures.category(s, "Shopping", CategoryKind.EXPENSE);
        UUID income = fixtures.category(s, "Salary", CategoryKind.INCOME);
        String mapping = "{\"Savings\":\"" + savings + "\"}";
        JsonNode rows = previewRows(s, bank, mapping);
        assertThat(row(rows, "To Savings").path("kind").asString()).isEqualTo("TRANSFER");
        var first = post(COMMIT, s, sample(), bank.toString(), mapping, decisions(everything(rows, expense, income)));
        assertThat(first).hasStatus(HttpStatus.CREATED);
        long transactions = count("SELECT count(*) FROM transactions");
        long batches = count("SELECT count(*) FROM import_batches");

        JsonNode again = previewRows(s, bank, mapping);

        assertThat(again.size()).isEqualTo(rows.size());
        assertThat(again).noneMatch(r -> r.path("status").asString().equals("NEW"));
        assertThat(again).noneMatch(r -> r.path("description").asString().equals("From Current"));
        assertThat(row(again, "To Savings").path("status").asString()).isEqualTo("ALREADY_IMPORTED");
        var second = post(COMMIT, s, sample(), bank.toString(), mapping, decisions(List.of()));
        assertThat(second).hasStatus(HttpStatus.CREATED);
        assertThat(body(second).path("created").asInt()).isZero();
        assertThat(count("SELECT count(*) FROM transactions")).isEqualTo(transactions);
        assertThat(count("SELECT count(*) FROM import_batches")).isEqualTo(batches);
    }

    private void assertSingleError(MvcTestResult result, String field, String code) throws Exception {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        JsonNode root = body(result);
        assertThat(root.path("code").asString()).isEqualTo("VALIDATION_FAILED");
        assertThat(root.path("errors").get(0).path("field").asString()).isEqualTo(field);
        assertThat(root.path("errors").get(0).path("code").asString()).isEqualTo(code);
    }

    @Test
    void commit_unknownRowId_returns400RowUnknown() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cat = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        JsonNode cafe = row(previewRows(s, bank, null), "Cafe Aurora");
        Map<String, Object> unknown = new LinkedHashMap<>();
        unknown.put("rowId", "deadbeef");
        unknown.put("include", true);
        unknown.put("categoryId", cat);

        var result = commit(s, bank, List.of(decision(cafe, true, cat), unknown));

        assertSingleError(result, "decisions[deadbeef]", "ROW_UNKNOWN");
        assertThat(count("SELECT count(*) FROM transactions")).isZero();
        assertThat(count("SELECT count(*) FROM import_batches")).isZero();
        assertThat(count("SELECT count(*) FROM category_memory")).isZero();
    }

    @Test
    void commit_alreadyImportedIncluded_returns400() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cat = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        JsonNode cafe = row(previewRows(s, bank, null), "Cafe Aurora");
        commit(s, bank, List.of(decision(cafe, true, cat)));

        var result = commit(s, bank, List.of(decision(cafe, true, cat)));

        assertSingleError(result, "decisions[" + cafe.path("rowId").asString() + "]", "ROW_ALREADY_IMPORTED");
        assertThat(count("SELECT count(*) FROM transactions")).isEqualTo(1);
    }

    @Test
    void commit_includedWithoutCategory_returns400() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        JsonNode cafe = row(previewRows(s, bank, null), "Cafe Aurora");

        var result = commit(s, bank, List.of(decision(cafe, true, null)));

        assertSingleError(result, "decisions[" + cafe.path("rowId").asString() + "]", "CATEGORY_REQUIRED");
        assertThat(count("SELECT count(*) FROM transactions")).isZero();
    }

    @Test
    void commit_categoryOnTransfer_returns400() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID savings = fixtures.bankAccount(s, "Savings", 0);
        UUID cat = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        String mapping = "{\"Savings\":\"" + savings + "\"}";
        JsonNode transfer = row(previewRows(s, bank, mapping), "To Savings");

        var result = post(COMMIT, s, sample(), bank.toString(), mapping, decisions(List.of(decision(transfer, true, cat))));

        assertSingleError(result, "decisions[" + transfer.path("rowId").asString() + "]", "CATEGORY_NOT_ALLOWED");
    }

    @Test
    void commit_archivedOrWrongKindOrForeignCategory_returns400() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        TestSession other = testUsers.verified("rui@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID archived = fixtures.category(s, "Old", CategoryKind.EXPENSE);
        jdbc.update("UPDATE categories SET archived_at = now() WHERE id = ?", archived);
        UUID income = fixtures.category(s, "Earnings", CategoryKind.INCOME);
        UUID foreign = fixtures.category(other, "Theirs", CategoryKind.EXPENSE);
        JsonNode rows = previewRows(s, bank, null);
        JsonNode cafe = row(rows, "Cafe Aurora");
        JsonNode mercado = row(rows, "Mercado Lua, Lda");
        JsonNode cinema = rowsNamed(rows, "Cinema Plaza").get(0);

        var result = commit(
                s, bank, List.of(decision(cafe, true, archived), decision(mercado, true, income), decision(cinema, true, foreign)));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        JsonNode errors = body(result).path("errors");
        assertThat(errors.size()).isEqualTo(3);
        assertThat(errors.get(0).path("field").asString()).isEqualTo("decisions[" + cafe.path("rowId").asString() + "]");
        assertThat(errors.get(0).path("code").asString()).isEqualTo("CATEGORY_ARCHIVED");
        assertThat(errors.get(1).path("code").asString()).isEqualTo("CATEGORY_KIND_MISMATCH");
        assertThat(errors.get(2).path("code").asString()).isEqualTo("NOT_FOUND");
        assertThat(count("SELECT count(*) FROM transactions")).isZero();
    }

    @Test
    void commit_memoryLastChoiceWins() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID first = fixtures.category(s, "First", CategoryKind.EXPENSE);
        UUID second = fixtures.category(s, "Second", CategoryKind.EXPENSE);
        List<JsonNode> cinemas = rowsNamed(previewRows(s, bank, null), "Cinema Plaza");

        commit(s, bank, List.of(decision(cinemas.get(0), true, first), decision(cinemas.get(1), true, second)));

        assertThat(count("SELECT count(*) FROM category_memory WHERE description_key = ?", DescriptionKeys.of("Cinema Plaza")))
                .isEqualTo(1);
        assertThat(jdbc.queryForObject(
                        "SELECT category_id FROM category_memory WHERE description_key = ?",
                        UUID.class,
                        DescriptionKeys.of("Cinema Plaza")))
                .isEqualTo(second);
    }

    @Test
    void commit_badDecisionsPart_returns400() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);

        for (String bad : List.of("{not json", "{\"rowId\":\"x\"}", "[{\"rowId\":\"x\",\"include\":true,\"categoryId\":\"nope\"}]")) {
            var result = post(COMMIT, s, sample(), bank.toString(), null, bad);
            assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
            assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        }
        var missing = post(COMMIT, s, sample(), bank.toString(), null, null);
        assertThat(missing).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void commit_archivedBankAccount_returns409() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        jdbc.update("UPDATE bank_accounts SET archived_at = now() WHERE id = ?", bank);

        var result = commit(s, bank, List.of());

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("BANK_ACCOUNT_ARCHIVED");
    }

    // ---- Revolut pockets: transfers with goal movements ----

    @Autowired
    MutableClock clock;

    private static final String PT_HEADER =
            "Tipo,Produto,Data de início,Data de Conclusão,Descrição,Montante,Comissão,Moeda,Estado,Saldo\n";
    private static final String RESERVA = "Carregamento de subconta EUR Reserva de Emergência de EUR";
    private static final String SETUP = "Carregamento de subconta EUR Setup de EUR";

    private static String leg(String product, String at, String description, String amount) {
        return "Transferência,%s,%s,%s,%s,%s,0.00,EUR,CONCLUÍDA,0.00\n"
                .formatted(product, at, at, description, amount);
    }

    private static byte[] pocketFile(String... legs) {
        return (PT_HEADER + String.join("", legs)).getBytes(StandardCharsets.UTF_8);
    }

    /** A deposit into the pocket: both legs, as the statement carries them. */
    private static byte[] deposit(String at, String description, String amount) {
        return pocketFile(leg("Poupanças", at, description, amount), leg("Atual", at, description, "-" + amount));
    }

    private static byte[] withdrawal(String at, String description, String amount) {
        return pocketFile(leg("Poupanças", at, description, "-" + amount), leg("Atual", at, description, amount));
    }

    private static String pocketsMapping(UUID pockets) {
        return "{\"Poupanças\":\"" + pockets + "\"}";
    }

    private MvcTestResult send(
            String uri, TestSession s, byte[] file, UUID bank, String mapping, UUID pocketsAccount, String decisions) {
        var builder = mvc.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .multipart()
                .file(new MockMultipartFile("file", "statement.csv", "text/csv", file))
                .file(new MockMultipartFile("bankAccountId", "", "text/plain", bank.toString().getBytes()));
        if (mapping != null) {
            builder = builder.file(new MockMultipartFile(
                    "productMapping", "", "application/json", mapping.getBytes(StandardCharsets.UTF_8)));
        }
        if (pocketsAccount != null) {
            builder = builder.file(new MockMultipartFile(
                    "pocketsBankAccountId", "", "text/plain", pocketsAccount.toString().getBytes()));
        }
        if (decisions != null) {
            builder = builder.file(new MockMultipartFile("decisions", "", "application/json", decisions.getBytes()));
        }
        return builder.exchange();
    }

    private JsonNode pocketPreview(TestSession s, byte[] file, UUID bank, String mapping, UUID pocketsAccount)
            throws Exception {
        var result = send(PREVIEW, s, file, bank, mapping, pocketsAccount, null);
        assertThat(result).hasStatus(HttpStatus.OK);
        return body(result).path("rows");
    }

    private static JsonNode transfer(JsonNode rows) {
        List<JsonNode> transfers = new ArrayList<>();
        for (JsonNode r : rows) {
            if (r.path("kind").asString().equals("TRANSFER")) {
                transfers.add(r);
            }
        }
        assertThat(transfers).hasSize(1);
        return transfers.get(0);
    }

    private static Map<String, Object> goalDecision(JsonNode row, boolean include, UUID goal) {
        Map<String, Object> m = decision(row, include, null);
        m.put("goalId", goal);
        return m;
    }

    /** Previews the file, includes its one pocket transfer with the goal and commits; returns the commit result. */
    private MvcTestResult commitPocket(TestSession s, byte[] file, UUID bank, String mapping, UUID goal)
            throws Exception {
        JsonNode t = transfer(pocketPreview(s, file, bank, mapping, null));
        return send(COMMIT, s, file, bank, mapping, null, decisions(List.of(goalDecision(t, true, goal))));
    }

    private UUID goal(TestSession s, String name, long target, int percent, String pocket) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("targetCents", target);
        m.put("monthlyPercent", percent);
        m.put("revolutPocket", pocket);
        var r = mvc.post()
                .uri("/api/v1/goals")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(m))
                .exchange();
        assertThat(r).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(body(r).path("id").asString());
    }

    private long saved(UUID goal) {
        return count(
                "SELECT coalesce(sum(CASE type WHEN 'WITHDRAWAL' THEN -amount_cents WHEN 'SKIP' THEN 0"
                        + " ELSE amount_cents END), 0) FROM goal_movements WHERE goal_id = ?",
                goal);
    }

    private UUID rememberedPockets(TestSession s) {
        return jdbc.queryForObject(
                "SELECT pockets_bank_account_id FROM accounts WHERE id = ?", UUID.class, s.accountId());
    }

    @Test
    void commit_pocketDeposit_createsLinkedContribution() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        UUID goal = goal(s, "Emergência", 500000, 0, "Reserva de Emergência");

        var result = commitPocket(
                s, deposit("2026-11-20 14:34:39", RESERVA, "250.00"), bank, pocketsMapping(pockets), goal);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(saved(goal)).isEqualTo(25000);
        var transfer = jdbc.queryForMap(
                "SELECT id, bank_account_id, destination_bank_account_id FROM transactions WHERE kind = 'TRANSFER'");
        assertThat(transfer.get("bank_account_id")).isEqualTo(bank);
        assertThat(transfer.get("destination_bank_account_id")).isEqualTo(pockets);
        var movement = jdbc.queryForMap(
                "SELECT type, amount_cents, transaction_id, occurred_on::text AS occurred_on FROM goal_movements"
                        + " WHERE goal_id = ?",
                goal);
        assertThat(movement.get("type")).isEqualTo("CONTRIBUTION");
        assertThat(movement.get("amount_cents")).isEqualTo(25000L);
        assertThat(movement.get("transaction_id")).isEqualTo(transfer.get("id"));
        assertThat(movement.get("occurred_on")).isEqualTo("2026-11-20");
    }

    @Test
    void commit_depositAboveTarget_accepted() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        UUID goal = goal(s, "Emergência", 100000, 0, "Reserva de Emergência");

        var result = commitPocket(
                s, deposit("2026-11-20 14:55:30", RESERVA, "1250.00"), bank, pocketsMapping(pockets), goal);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(saved(goal)).isEqualTo(125000);
    }

    @Test
    void commit_withdrawalAboveSaved_clampedToSaved() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        UUID goal = goal(s, "Emergência", 500000, 0, "Reserva de Emergência");
        var contribution = mvc.post()
                .uri("/api/v1/goals/" + goal + "/contributions")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"amountCents\":10000}")
                .exchange();
        assertThat(contribution).hasStatus(HttpStatus.CREATED);

        var result = commitPocket(
                s, withdrawal("2026-11-21 09:00:00", RESERVA, "300.00"), bank, pocketsMapping(pockets), goal);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        var transfer = jdbc.queryForMap("SELECT amount_cents, bank_account_id, destination_bank_account_id"
                + " FROM transactions WHERE kind = 'TRANSFER'");
        assertThat(transfer.get("amount_cents")).isEqualTo(30000L);
        assertThat(transfer.get("bank_account_id")).isEqualTo(pockets);
        assertThat(transfer.get("destination_bank_account_id")).isEqualTo(bank);
        assertThat(count("SELECT amount_cents FROM goal_movements WHERE goal_id = ? AND type = 'WITHDRAWAL'", goal))
                .isEqualTo(10000);
        assertThat(saved(goal)).isZero();
    }

    @Test
    void commit_withdrawal_goalAtZero_noMovement() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        UUID goal = goal(s, "Emergência", 500000, 0, "Reserva de Emergência");

        var result = commitPocket(
                s, withdrawal("2026-11-21 09:00:00", RESERVA, "300.00"), bank, pocketsMapping(pockets), goal);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(count("SELECT count(*) FROM transactions WHERE kind = 'TRANSFER'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM goal_movements WHERE goal_id = ?", goal)).isZero();
    }

    @Test
    void commit_nameMatch_savesPocketOnGoal() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        UUID goal = goal(s, "setup", 150000, 0, null);
        byte[] file = deposit("2026-11-20 14:54:41", SETUP, "100.00");
        JsonNode t = transfer(pocketPreview(s, file, bank, pocketsMapping(pockets), null));
        assertThat(t.path("pocket").path("match").asString()).isEqualTo("NAME_MATCH");
        assertThat(t.path("pocket").path("goalId").asString()).isEqualTo(goal.toString());

        var result = send(
                COMMIT, s, file, bank, pocketsMapping(pockets), null, decisions(List.of(goalDecision(t, true, goal))));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT revolut_pocket FROM goals WHERE id = ?", String.class, goal))
                .isEqualTo("Setup");
        assertThat(saved(goal)).isEqualTo(10000);
    }

    @Test
    void commit_pocketNameTakenByAnotherGoal_notSaved() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        goal(s, "Computador", 150000, 0, "Setup");
        UUID chosen = goal(s, "Outro", 150000, 0, null);
        byte[] file = deposit("2026-11-20 14:54:41", SETUP, "100.00");

        var result = commitPocket(s, file, bank, pocketsMapping(pockets), chosen);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(jdbc.queryForObject("SELECT revolut_pocket FROM goals WHERE id = ?", String.class, chosen))
                .isNull();
        assertThat(saved(chosen)).isEqualTo(10000);
    }

    @Test
    void commit_savingsMapping_remembered() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        byte[] file = deposit("2026-11-20 14:54:41", SETUP, "100.00");
        JsonNode t = transfer(pocketPreview(s, file, bank, pocketsMapping(pockets), null));

        var result = send(
                COMMIT, s, file, bank, pocketsMapping(pockets), null, decisions(List.of(decision(t, true, null))));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(rememberedPockets(s)).isEqualTo(pockets);
        assertThat(count("SELECT count(*) FROM goal_movements")).isZero();
    }

    @Test
    void commit_pocketsAccountParam_usedAndRemembered() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        byte[] file = pocketFile(leg("Atual", "2026-11-20 14:54:41", SETUP, "-100.00"));
        JsonNode t = transfer(pocketPreview(s, file, bank, null, pockets));
        assertThat(t.path("destinationBankAccountId").asString()).isEqualTo(pockets.toString());

        var result = send(COMMIT, s, file, bank, null, pockets, decisions(List.of(decision(t, true, null))));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        var stored = jdbc.queryForMap(
                "SELECT bank_account_id, destination_bank_account_id FROM transactions WHERE kind = 'TRANSFER'");
        assertThat(stored.get("bank_account_id")).isEqualTo(bank);
        assertThat(stored.get("destination_bank_account_id")).isEqualTo(pockets);
        assertThat(rememberedPockets(s)).isEqualTo(pockets);
    }

    @Test
    void commit_pocketsAccountParam_foreign_returns404() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        TestSession other = testUsers.verified("rui@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID theirs = fixtures.bankAccount(other, "Theirs", 0);
        byte[] file = pocketFile(leg("Atual", "2026-11-20 14:54:41", SETUP, "-100.00"));

        var result = send(COMMIT, s, file, bank, null, theirs, decisions(List.of()));

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(rememberedPockets(s)).isNull();
    }

    @Test
    void commit_goalOnNonPocketRow_rejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cafes = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        UUID goal = goal(s, "Emergência", 500000, 0, "Reserva de Emergência");
        JsonNode cafe = row(previewRows(s, bank, null), "Cafe Aurora");
        Map<String, Object> d = decision(cafe, true, cafes);
        d.put("goalId", goal);

        var result = commit(s, bank, List.of(d));

        assertSingleError(result, "decisions[" + cafe.path("rowId").asString() + "]", "GOAL_NOT_ALLOWED");
        assertThat(count("SELECT count(*) FROM transactions")).isZero();
    }

    @Test
    void commit_completedGoal_rejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        UUID goal = goal(s, "Emergência", 500000, 0, "Reserva de Emergência");
        var complete = mvc.post()
                .uri("/api/v1/goals/" + goal + "/complete")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();
        assertThat(complete).hasStatus(HttpStatus.OK);
        byte[] file = deposit("2026-11-20 14:34:39", RESERVA, "250.00");
        JsonNode t = transfer(pocketPreview(s, file, bank, pocketsMapping(pockets), null));
        assertThat(t.path("pocket").path("goalId").isNull()).isTrue();

        var result = send(
                COMMIT, s, file, bank, pocketsMapping(pockets), null, decisions(List.of(goalDecision(t, true, goal))));

        assertSingleError(result, "decisions[" + t.path("rowId").asString() + "]", "GOAL_INVALID");
        assertThat(count("SELECT count(*) FROM transactions")).isZero();
        assertThat(count("SELECT count(*) FROM goal_movements")).isZero();
    }

    @Test
    void commit_foreignOrUnknownGoal_rejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        TestSession other = testUsers.verified("rui@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        UUID theirs = goal(other, "Emergência", 500000, 0, "Reserva de Emergência");
        byte[] file = deposit("2026-11-20 14:34:39", RESERVA, "250.00");
        JsonNode t = transfer(pocketPreview(s, file, bank, pocketsMapping(pockets), null));

        for (UUID bad : List.of(theirs, UUID.randomUUID())) {
            var result = send(
                    COMMIT, s, file, bank, pocketsMapping(pockets), null, decisions(List.of(goalDecision(t, true, bad))));
            assertSingleError(result, "decisions[" + t.path("rowId").asString() + "]", "GOAL_INVALID");
        }
        assertThat(count("SELECT count(*) FROM goal_movements")).isZero();
    }

    @Test
    void commit_reimport_noSecondMovement() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        UUID goal = goal(s, "Emergência", 500000, 0, "Reserva de Emergência");
        byte[] file = deposit("2026-11-20 14:34:39", RESERVA, "250.00");
        assertThat(commitPocket(s, file, bank, pocketsMapping(pockets), goal)).hasStatus(HttpStatus.CREATED);

        JsonNode again = transfer(pocketPreview(s, file, bank, pocketsMapping(pockets), null));
        assertThat(again.path("status").asString()).isEqualTo("ALREADY_IMPORTED");
        var second = send(
                COMMIT, s, file, bank, pocketsMapping(pockets), null, decisions(List.of(goalDecision(again, false, goal))));

        assertThat(second).hasStatus(HttpStatus.CREATED);
        assertThat(body(second).path("created").asInt()).isZero();
        assertThat(count("SELECT count(*) FROM goal_movements WHERE goal_id = ?", goal)).isEqualTo(1);
        assertThat(saved(goal)).isEqualTo(25000);
    }

    @Test
    void commit_sameDayDepositThenWithdrawal_endsAtZero() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        UUID goal = goal(s, "Emergência", 500000, 0, "Reserva de Emergência");
        // newest first, as Revolut exports list them
        byte[] file = pocketFile(
                leg("Poupanças", "2026-11-20 18:00:00", RESERVA, "-100.00"),
                leg("Atual", "2026-11-20 18:00:00", RESERVA, "100.00"),
                leg("Poupanças", "2026-11-20 09:00:00", RESERVA, "100.00"),
                leg("Atual", "2026-11-20 09:00:00", RESERVA, "-100.00"));
        JsonNode rows = pocketPreview(s, file, bank, pocketsMapping(pockets), null);
        List<Map<String, Object>> list = new ArrayList<>();
        for (JsonNode r : rows) {
            assertThat(r.path("kind").asString()).isEqualTo("TRANSFER");
            list.add(goalDecision(r, true, goal));
        }
        assertThat(list).hasSize(2);

        var result = send(COMMIT, s, file, bank, pocketsMapping(pockets), null, decisions(list));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(count("SELECT count(*) FROM goal_movements WHERE goal_id = ?", goal)).isEqualTo(2);
        assertThat(saved(goal)).isZero();
    }

    @Test
    void commit_partnerLegArrivesLater_noSecondMovement() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        UUID cafes = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        UUID goal = goal(s, "Emergência", 500000, 0, "Reserva de Emergência");
        String at = "2026-11-20 14:55:30";
        byte[] first = pocketFile(leg("Poupanças", at, RESERVA, "1000.00"));
        assertThat(commitPocket(s, first, bank, pocketsMapping(pockets), goal)).hasStatus(HttpStatus.CREATED);
        assertThat(count("SELECT count(*) FROM transactions WHERE kind = 'TRANSFER'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM goal_movements WHERE goal_id = ?", goal)).isEqualTo(1);

        byte[] later = pocketFile(
                leg("Atual", at, RESERVA, "-1000.00"),
                leg("Poupanças", at, RESERVA, "1000.00"),
                "CARD_PAYMENT,Atual,2026-11-21 10:00:00,2026-11-21 10:00:01,Cafe Aurora,-3.50,0.00,EUR,CONCLUÍDA,0.00\n");
        JsonNode rows = pocketPreview(s, later, bank, pocketsMapping(pockets), null);
        JsonNode t = transfer(rows);
        assertThat(t.path("status").asString()).isEqualTo("ALREADY_IMPORTED");
        var result = send(COMMIT, s, later, bank, pocketsMapping(pockets), null,
                decisions(List.of(decision(row(rows, "Cafe Aurora"), true, cafes))));

        assertThat(result).hasStatus(HttpStatus.CREATED);
        assertThat(body(result).path("created").asInt()).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transactions WHERE kind = 'TRANSFER'")).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM goal_movements WHERE goal_id = ?", goal)).isEqualTo(1);
        assertThat(saved(goal)).isEqualTo(100000);
    }

    @Test
    void commit_goalCreatedToday_acceptsEarlierMonths() throws Exception {
        clock.setInstant(Instant.parse("2026-11-26T12:00:00Z"));
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        UUID goal = goal(s, "Emergência", 500000, 10, "Reserva de Emergência");

        var result = commitPocket(
                s, deposit("2026-10-20 09:00:00", RESERVA, "50.00"), bank, pocketsMapping(pockets), goal);

        assertThat(result).hasStatus(HttpStatus.CREATED);
        var movement = jdbc.queryForMap("SELECT type, month::text AS month FROM goal_movements WHERE goal_id = ?", goal);
        assertThat(movement.get("type")).isEqualTo("CONTRIBUTION");
        assertThat(movement.get("month")).isEqualTo("2026-10-01");
        var overview = mvc.get()
                .uri("/api/v1/goals?month=2026-10")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();
        assertThat(overview).hasStatus(HttpStatus.OK);
        JsonNode targets = body(overview).path("monthTargets");
        assertThat(targets.size()).isEqualTo(1);
        assertThat(targets.get(0).path("goalId").asString()).isEqualTo(goal.toString());
        assertThat(targets.get(0).path("contributedCents").asLong()).isEqualTo(5000);
    }
}
