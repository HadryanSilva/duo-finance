package br.dev.duofinance.imports.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.imports.service.DescriptionKeys;
import br.dev.duofinance.imports.service.RevolutCsvParser;
import br.dev.duofinance.imports.service.RowFingerprints;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
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
class ImportPreviewTest {

    private static final String URI = "/api/v1/imports/revolut/preview";

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

    private MvcTestResult preview(TestSession s, byte[] file, String bankAccountId, String mapping) {
        var builder = mvc.post()
                .uri(URI)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .multipart()
                .file(new MockMultipartFile("file", "statement.csv", "text/csv", file))
                .file(new MockMultipartFile("bankAccountId", "", "text/plain", bankAccountId.getBytes()));
        if (mapping != null) {
            builder = builder.file(new MockMultipartFile("productMapping", "", "application/json", mapping.getBytes()));
        }
        return builder.exchange();
    }

    private JsonNode body(MvcTestResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private static JsonNode row(JsonNode root, String description) {
        for (JsonNode r : root.path("rows")) {
            if (description.equals(r.path("description").asString())) {
                return r;
            }
        }
        throw new AssertionError("no row " + description);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
    }

    @Test
    void preview_returnsRowsSkippedAndProducts_andWritesNothing() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        long tx = count("transactions");
        long batches = count("import_batches");
        long memory = count("category_memory");

        var result = preview(s, sample(), bank.toString(), null);

        assertThat(result).hasStatus(HttpStatus.OK);
        JsonNode root = body(result);
        assertThat(root.path("products").size()).isEqualTo(2);
        assertThat(root.path("products").get(0).path("product").asString()).isEqualTo("Current");
        assertThat(root.path("products").get(0).path("bankAccountId").asString())
                .isEqualTo(bank.toString());
        assertThat(root.path("products").get(1).path("product").asString()).isEqualTo("Savings");
        assertThat(root.path("products").get(1).path("bankAccountId").isNull()).isTrue();
        JsonNode cafe = row(root, "Cafe Aurora");
        assertThat(cafe.path("kind").asString()).isEqualTo("EXPENSE");
        assertThat(cafe.path("amountCents").asLong()).isEqualTo(1250);
        assertThat(cafe.path("status").asString()).isEqualTo("NEW");
        assertThat(cafe.path("descriptionKey").asString()).isNotBlank();
        assertThat(cafe.path("rowId").asString()).isNotBlank();
        assertThat(root.path("skipped").path("counts").path("PENDING").asInt()).isEqualTo(1);
        assertThat(root.path("skipped").path("counts").path("OTHER_CURRENCY").asInt())
                .isEqualTo(1);
        assertThat(root.path("skipped").path("rows").size()).isGreaterThan(0);

        assertThat(count("transactions")).isEqualTo(tx);
        assertThat(count("import_batches")).isEqualTo(batches);
        assertThat(count("category_memory")).isEqualTo(memory);
    }

    @Test
    void preview_knownFingerprintAndManualMatch_flagged() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID category = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        var rows = RevolutCsvParser.parse(sample());
        String fingerprint = RowFingerprints.of(rows).get(0);
        UUID imported = fixtures.transaction(
                s,
                Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        1250,
                        "date",
                        "2026-10-04",
                        "categoryId",
                        category,
                        "bankAccountId",
                        bank));
        jdbc.update("UPDATE transactions SET import_fingerprint = ? WHERE id = ?", fingerprint, imported);
        fixtures.transaction(
                s,
                Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        4530,
                        "date",
                        "2026-10-06",
                        "categoryId",
                        category,
                        "bankAccountId",
                        bank));

        JsonNode root = body(preview(s, sample(), bank.toString(), null));

        assertThat(row(root, "Cafe Aurora").path("status").asString()).isEqualTo("ALREADY_IMPORTED");
        assertThat(row(root, "Mercado Lua, Lda").path("status").asString()).isEqualTo("POSSIBLE_DUPLICATE");
        assertThat(row(root, "Top-Up by *1234").path("status").asString()).isEqualTo("NEW");
    }

    @Test
    void preview_manualOnPurchaseDay_flaggedWhenCompletedNextDay() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID category = fixtures.category(s, "Compras", CategoryKind.EXPENSE);
        fixtures.transaction(
                s,
                Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        6197,
                        "date",
                        "2026-11-15",
                        "categoryId",
                        category,
                        "bankAccountId",
                        bank));
        String csv = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance\n"
                + "CARD_PAYMENT,Current,2026-11-15 17:52:08,2026-11-16 09:59:43,Lefties,-61.97,0.00,EUR,COMPLETED,1\n";

        JsonNode root = body(preview(s, csv.getBytes(), bank.toString(), null));

        assertThat(row(root, "Lefties").path("status").asString()).isEqualTo("POSSIBLE_DUPLICATE");
    }

    @Test
    void preview_memorySuggestion_usedOnlyWhenCategoryActive() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID category = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        jdbc.update(
                "INSERT INTO category_memory (account_id, description_key, kind, category_id, updated_at)"
                        + " VALUES (?, ?, 'EXPENSE', ?, now())",
                s.accountId(),
                DescriptionKeys.of("Cafe Aurora"),
                category);

        JsonNode root = body(preview(s, sample(), bank.toString(), null));
        assertThat(row(root, "Cafe Aurora").path("suggestedCategoryId").asString())
                .isEqualTo(category.toString());

        jdbc.update("UPDATE categories SET archived_at = now() WHERE id = ?", category);
        root = body(preview(s, sample(), bank.toString(), null));
        UUID fallback = jdbc.queryForObject(
                "SELECT id FROM categories WHERE account_id = ? AND kind = 'EXPENSE' AND system_default AND name IN ('Outros', 'Other')"
                        + " AND archived_at IS NULL",
                UUID.class,
                s.accountId());
        assertThat(row(root, "Cafe Aurora").path("suggestedCategoryId").asString())
                .isEqualTo(fallback.toString());
    }

    @Test
    void preview_mappedProduct_usesMappedAccount() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID savings = fixtures.bankAccount(s, "Savings", 0);

        JsonNode root = body(preview(s, sample(), bank.toString(), "{\"Savings\":\"" + savings + "\"}"));

        assertThat(root.path("products").get(1).path("bankAccountId").asString())
                .isEqualTo(savings.toString());
        assertThat(row(root, "To Savings").path("kind").asString()).isEqualTo("TRANSFER");
    }

    @Test
    void preview_foreignBankAccount_returns404() throws Exception {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession intruder = testUsers.verified("rui@mail.com");
        UUID foreign = fixtures.bankAccount(owner, "Conta", 0);
        UUID own = fixtures.bankAccount(intruder, "Minha", 0);

        var result = preview(intruder, sample(), foreign.toString(), null);
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");

        var mapped = preview(intruder, sample(), own.toString(), "{\"Savings\":\"" + foreign + "\"}");
        assertThat(mapped).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void preview_archivedBankAccount_returns409() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        jdbc.update("UPDATE bank_accounts SET archived_at = now() WHERE id = ?", bank);

        var result = preview(s, sample(), bank.toString(), null);

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("BANK_ACCOUNT_ARCHIVED");
    }

    @Test
    void preview_otherFormat_returns422FormatUnsupported() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);

        var result = preview(s, "a,b,c\n1,2,3\n".getBytes(), bank.toString(), null);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("IMPORT_FORMAT_UNSUPPORTED");
    }

    @Test
    void preview_invalidRow_problemCarriesLine() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        String csv = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance\n"
                + "CARD_PAYMENT,Current,2026-10-03 12:04:51,2026-10-04 08:15:02,Cafe,-1.00,0.00,EUR,COMPLETED,1\n"
                + "CARD_PAYMENT,Current,2026-10-03 12:04:51,2026-10-04 08:15:02,Cafe,abc,0.00,EUR,COMPLETED,1\n";

        var result = preview(s, csv.getBytes(), bank.toString(), null);

        assertThat(result).hasStatus(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("IMPORT_ROW_INVALID");
        assertThat(result).bodyJson().extractingPath("$.line").isEqualTo(3);
    }

    @Test
    void preview_badParts_return400() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);

        var badId = preview(s, sample(), "not-a-uuid", null);
        assertThat(badId).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(badId).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");

        var badMapping = preview(s, sample(), bank.toString(), "{not json");
        assertThat(badMapping).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(badMapping).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");

        var badValue = preview(s, sample(), bank.toString(), "{\"Savings\":\"nope\"}");
        assertThat(badValue).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(badValue).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void preview_missingFilePart_returns400() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);

        var result = mvc.post()
                .uri(URI)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .multipart()
                .file(new MockMultipartFile(
                        "bankAccountId", "", "text/plain", bank.toString().getBytes()))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    }

    @Test
    void preview_missingBankAccountIdPart_returns400() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");

        var result = mvc.post()
                .uri(URI)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .multipart()
                .file(new MockMultipartFile("file", "statement.csv", "text/csv", sample()))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
    }

    /**
     * MockMvc does not enforce the servlet container's multipart limits, so the oversize upload is exercised at the
     * handler: the exception the container raises must answer 413 with a stable code, never 500.
     */
    @Test
    void preview_fileOver1MB_returns413() throws Exception {
        var handler = new br.dev.duofinance.shared.error.GlobalExceptionHandler();
        var request = new org.springframework.web.context.request.ServletWebRequest(
                new org.springframework.mock.web.MockHttpServletRequest());

        var response = handler.handleException(
                new org.springframework.web.multipart.MaxUploadSizeExceededException(1_048_576), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONTENT_TOO_LARGE);
        assertThat(response.getBody()).isInstanceOf(org.springframework.http.ProblemDetail.class);
        var problem = (org.springframework.http.ProblemDetail) response.getBody();
        assertThat(problem.getProperties()).containsEntry("code", "VALIDATION_FAILED");
    }

    /** The file stays at 1 MB; the commit request (file + decisions) may reach 2 MB and is kept in memory. */
    @Test
    void multipartLimits_fileOneMegabyteRequestTwoInMemory(
            @Value("${spring.servlet.multipart.max-file-size}") String maxFile,
            @Value("${spring.servlet.multipart.max-request-size}") String maxRequest,
            @Value("${spring.servlet.multipart.file-size-threshold}") String threshold) {
        assertThat(maxFile).isEqualTo("1MB");
        assertThat(maxRequest).isEqualTo("2MB");
        assertThat(threshold).isEqualTo("2MB");
    }

    @Test
    void preview_inactiveSubscription_returns402() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        jdbc.update("UPDATE subscriptions SET status = 'EXPIRED' WHERE account_id = ?", s.accountId());

        var result = preview(s, sample(), bank.toString(), null);

        assertThat(result).hasStatus(HttpStatus.PAYMENT_REQUIRED);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("SUBSCRIPTION_INACTIVE");
    }

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

    private UUID goal(TestSession s, String name, String pocket) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("targetCents", 500000);
        m.put("monthlyPercent", 0);
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

    private static JsonNode transferNamed(JsonNode root, String description) {
        for (JsonNode r : root.path("rows")) {
            if (r.path("kind").asString().equals("TRANSFER") && description.equals(r.path("description").asString())) {
                return r;
            }
        }
        throw new AssertionError("no transfer " + description);
    }

    @Test
    void preview_pocketRows_carryPocketInfo() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        UUID goal = goal(s, "Emergência", "Reserva de Emergência");
        byte[] file = pocketFile(
                leg("Poupanças", "2026-11-20 14:34:39", RESERVA, "250.00"),
                leg("Poupanças", "2026-11-20 14:54:41", SETUP, "100.00"),
                leg("Atual", "2026-11-20 14:34:39", RESERVA, "-250.00"),
                leg("Atual", "2026-11-20 14:54:41", SETUP, "-100.00"),
                "CARD_PAYMENT,Atual,2026-11-21 10:00:00,2026-11-21 10:00:01,Cafe Aurora,-3.50,0.00,EUR,CONCLUÍDA,0.00\n");

        var result = preview(s, file, bank.toString(), "{\"Poupanças\":\"" + pockets + "\"}");

        assertThat(result).hasStatus(HttpStatus.OK);
        JsonNode root = body(result);
        JsonNode reserva = transferNamed(root, RESERVA).path("pocket");
        assertThat(reserva.path("name").asString()).isEqualTo("Reserva de Emergência");
        assertThat(reserva.path("direction").asString()).isEqualTo("INTO");
        assertThat(reserva.path("goalId").asString()).isEqualTo(goal.toString());
        assertThat(reserva.path("match").asString()).isEqualTo("LINKED");
        JsonNode setup = transferNamed(root, SETUP).path("pocket");
        assertThat(setup.path("name").asString()).isEqualTo("Setup");
        assertThat(setup.path("goalId").isNull()).isTrue();
        assertThat(setup.path("match").asString()).isEqualTo("NONE");
        assertThat(row(root, "Cafe Aurora").path("pocket").isNull()).isTrue();
    }

    @Test
    void preview_rememberedPocketsAccount_fillsSavingsMapping() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID pockets = fixtures.bankAccount(s, "Pockets", 0);
        jdbc.update("UPDATE accounts SET pockets_bank_account_id = ? WHERE id = ?", pockets, s.accountId());
        byte[] file = pocketFile(
                leg("Poupanças", "2026-11-20 14:34:39", RESERVA, "250.00"),
                leg("Atual", "2026-11-20 14:34:39", RESERVA, "-250.00"));

        var result = preview(s, file, bank.toString(), null);

        assertThat(result).hasStatus(HttpStatus.OK);
        JsonNode root = body(result);
        JsonNode products = root.path("products");
        assertThat(products.size()).isEqualTo(2);
        assertThat(products.get(0).path("product").asString()).isEqualTo("Poupanças");
        assertThat(products.get(0).path("bankAccountId").asString()).isEqualTo(pockets.toString());
        assertThat(products.get(1).path("product").asString()).isEqualTo("Current");
        assertThat(products.get(1).path("bankAccountId").asString()).isEqualTo(bank.toString());
        JsonNode transfer = transferNamed(root, RESERVA);
        assertThat(transfer.path("bankAccountId").asString()).isEqualTo(bank.toString());
        assertThat(transfer.path("destinationBankAccountId").asString()).isEqualTo(pockets.toString());
    }

    @Test
    void preview_pocketsAccountParam_overridesRemembered() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID remembered = fixtures.bankAccount(s, "Old pockets", 0);
        UUID chosen = fixtures.bankAccount(s, "Pockets", 0);
        jdbc.update("UPDATE accounts SET pockets_bank_account_id = ? WHERE id = ?", remembered, s.accountId());
        byte[] file = pocketFile(leg("Atual", "2026-11-20 14:54:41", SETUP, "-100.00"));

        var result = mvc.post()
                .uri(URI)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .multipart()
                .file(new MockMultipartFile("file", "statement.csv", "text/csv", file))
                .file(new MockMultipartFile("bankAccountId", "", "text/plain", bank.toString().getBytes()))
                .file(new MockMultipartFile("pocketsBankAccountId", "", "text/plain", chosen.toString().getBytes()))
                .exchange();

        assertThat(result).hasStatus(HttpStatus.OK);
        assertThat(transferNamed(body(result), SETUP).path("destinationBankAccountId").asString())
                .isEqualTo(chosen.toString());
        assertThat(jdbc.queryForObject(
                        "SELECT pockets_bank_account_id FROM accounts WHERE id = ?", UUID.class, s.accountId()))
                .isEqualTo(remembered);
    }
}
