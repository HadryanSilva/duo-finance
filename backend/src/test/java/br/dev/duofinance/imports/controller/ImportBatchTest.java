package br.dev.duofinance.imports.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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
class ImportBatchTest {

    private static final String PREVIEW = "/api/v1/imports/revolut/preview";
    private static final String COMMIT = "/api/v1/imports/revolut/commit";
    private static final String IMPORTS = "/api/v1/imports";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    @Autowired
    JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    private MvcTestResult multipart(String uri, TestSession s, UUID bank, String decisions) throws Exception {
        byte[] file = Files.readAllBytes(Path.of("src/test/resources/imports/revolut-sample.csv"));
        var builder = mvc.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .multipart()
                .file(new MockMultipartFile("file", "statement.csv", "text/csv", file))
                .file(new MockMultipartFile(
                        "bankAccountId", "", "text/plain", bank.toString().getBytes()));
        if (decisions != null) {
            builder = builder.file(new MockMultipartFile("decisions", "", "application/json", decisions.getBytes()));
        }
        return builder.exchange();
    }

    private JsonNode body(MvcTestResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    /** Imports the named expense rows into {@code category}; returns the new batch id. */
    private UUID importRows(TestSession s, UUID bank, UUID category, String... descriptions) throws Exception {
        JsonNode rows = body(multipart(PREVIEW, s, bank, null)).path("rows");
        List<Map<String, Object>> decisions = new ArrayList<>();
        for (String description : descriptions) {
            for (JsonNode r : rows) {
                if (description.equals(r.path("description").asString())) {
                    decisions.add(Map.of("rowId", r.path("rowId").asString(), "include", true, "categoryId", category));
                    break;
                }
            }
        }
        assertThat(decisions).hasSize(descriptions.length);
        var result = multipart(COMMIT, s, bank, json.writeValueAsString(decisions));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(body(result).path("batchId").asString());
    }

    private MvcTestResult undo(TestSession s, UUID batchId) {
        return mvc.post()
                .uri(IMPORTS + "/" + batchId + "/undo")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();
    }

    private MvcTestResult list(TestSession s) {
        return mvc.get()
                .uri(IMPORTS)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();
    }

    private long count(String sql, Object... args) {
        return jdbc.queryForObject(sql, Long.class, args);
    }

    @Test
    void list_newestFirst_withCreatorName() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cafes = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        UUID first = importRows(s, bank, cafes, "Cafe Aurora");
        UUID second = importRows(s, bank, cafes, "Mercado Lua, Lda");

        var result = list(s);

        assertThat(result).hasStatus(HttpStatus.OK);
        JsonNode content = body(result).path("content");
        assertThat(content).hasSize(2);
        assertThat(content.get(0).path("id").asString()).isEqualTo(second.toString());
        assertThat(content.get(1).path("id").asString()).isEqualTo(first.toString());
        JsonNode newest = content.get(0);
        assertThat(newest.path("source").asString()).isEqualTo("REVOLUT");
        assertThat(newest.path("bankAccountId").asString()).isEqualTo(bank.toString());
        assertThat(newest.path("createdBy").asString()).isEqualTo(s.userId().toString());
        assertThat(newest.path("createdByName").asString()).isNotBlank();
        assertThat(newest.path("rowCount").asInt()).isEqualTo(1);
        assertThat(newest.path("undoneAt").isNull()).isTrue();
        assertThat(body(result).path("totalElements").asInt()).isEqualTo(2);
    }

    @Test
    void list_creatorDeleted_hasNullName() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cafes = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        UUID batch = importRows(s, bank, cafes, "Cafe Aurora");
        // what ON DELETE SET NULL leaves behind when the creator's user row is removed
        jdbc.update("UPDATE import_batches SET created_by = NULL WHERE id = ?", batch);

        JsonNode item = body(list(s)).path("content").get(0);

        assertThat(item.path("createdBy").isNull()).isTrue();
        assertThat(item.path("createdByName").isNull()).isTrue();
    }

    @Test
    void list_onlyOwnAccount() throws Exception {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession rui = testUsers.verified("rui@mail.com");
        UUID bank = fixtures.bankAccount(ana, "Revolut", 0);
        UUID cafes = fixtures.category(ana, "Cafes", CategoryKind.EXPENSE);
        importRows(ana, bank, cafes, "Cafe Aurora");

        assertThat(body(list(rui)).path("content")).isEmpty();
    }

    @Test
    void undo_deletesOnlyThatBatchIncludingEditedRows() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cafes = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        UUID kept = importRows(s, bank, cafes, "Cafe Aurora");
        UUID batch = importRows(s, bank, cafes, "Mercado Lua, Lda");
        UUID manual = fixtures.transaction(
                s,
                Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        100,
                        "date",
                        "2026-11-01",
                        "categoryId",
                        cafes,
                        "bankAccountId",
                        bank));
        UUID edited = jdbc.queryForObject("SELECT id FROM transactions WHERE import_batch_id = ?", UUID.class, batch);
        var put = mvc.put()
                .uri("/api/v1/transactions/" + edited)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        999,
                        "date",
                        "2026-11-02",
                        "categoryId",
                        cafes,
                        "bankAccountId",
                        bank,
                        "description",
                        "Edited by hand")))
                .exchange();
        assertThat(put).hasStatus(HttpStatus.OK);

        assertThat(undo(s, batch)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(count("SELECT count(*) FROM transactions WHERE import_batch_id = ?", batch))
                .isZero();
        assertThat(count("SELECT count(*) FROM transactions WHERE id = ?", edited))
                .isZero();
        assertThat(count("SELECT count(*) FROM transactions WHERE import_batch_id = ?", kept))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM transactions WHERE id = ?", manual))
                .isEqualTo(1);
        assertThat(count("SELECT count(*) FROM import_batches WHERE id = ? AND undone_at IS NOT NULL", batch))
                .isEqualTo(1);
        JsonNode listed = body(list(s)).path("content");
        assertThat(listed.get(0).path("undoneAt").isNull()).isFalse();
    }

    @Test
    void undo_twice_returns409AlreadyUndone() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cafes = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        UUID batch = importRows(s, bank, cafes, "Cafe Aurora");
        assertThat(undo(s, batch)).hasStatus(HttpStatus.NO_CONTENT);

        var again = undo(s, batch);

        assertThat(again).hasStatus(HttpStatus.CONFLICT);
        assertThat(again).bodyJson().extractingPath("$.code").isEqualTo("IMPORT_ALREADY_UNDONE");
    }

    @Test
    void undo_foreignBatch_returns404() throws Exception {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession intruder = testUsers.verified("rui@mail.com");
        UUID bank = fixtures.bankAccount(owner, "Revolut", 0);
        UUID cafes = fixtures.category(owner, "Cafes", CategoryKind.EXPENSE);
        UUID batch = importRows(owner, bank, cafes, "Cafe Aurora");

        var result = undo(intruder, batch);

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
        assertThat(count("SELECT count(*) FROM transactions WHERE import_batch_id = ?", batch))
                .isEqualTo(1);
    }

    @Test
    void undo_unknownBatch_returns404() {
        TestSession s = testUsers.verified("ana@mail.com");

        assertThat(undo(s, UUID.randomUUID())).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void undo_byPartner_works() throws Exception {
        TestSession[] couple = testUsers.couple("ana@mail.com", "rui@mail.com");
        UUID bank = fixtures.bankAccount(couple[0], "Revolut", 0);
        UUID cafes = fixtures.category(couple[0], "Cafes", CategoryKind.EXPENSE);
        UUID batch = importRows(couple[0], bank, cafes, "Cafe Aurora");

        assertThat(undo(couple[1], batch)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(count("SELECT count(*) FROM transactions WHERE import_batch_id = ?", batch))
                .isZero();
    }

    @Test
    void undo_thenReimport_createsRowsAgain() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cafes = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        UUID batch = importRows(s, bank, cafes, "Cafe Aurora");
        assertThat(undo(s, batch)).hasStatus(HttpStatus.NO_CONTENT);

        UUID again = importRows(s, bank, cafes, "Cafe Aurora");

        assertThat(again).isNotEqualTo(batch);
        assertThat(count("SELECT count(*) FROM transactions WHERE import_batch_id = ?", again))
                .isEqualTo(1);
    }

    @Test
    void undo_keepsCategoryMemory() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID cafes = fixtures.category(s, "Cafes", CategoryKind.EXPENSE);
        UUID batch = importRows(s, bank, cafes, "Cafe Aurora");
        long before = count("SELECT count(*) FROM category_memory WHERE account_id = ?", s.accountId());
        assertThat(before).isPositive();

        assertThat(undo(s, batch)).hasStatus(HttpStatus.NO_CONTENT);

        assertThat(count("SELECT count(*) FROM category_memory WHERE account_id = ?", s.accountId()))
                .isEqualTo(before);
    }
}
