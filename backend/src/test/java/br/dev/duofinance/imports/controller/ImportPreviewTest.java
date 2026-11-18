package br.dev.duofinance.imports.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.imports.service.DescriptionKeys;
import br.dev.duofinance.imports.service.RevolutCsvParser;
import br.dev.duofinance.imports.service.RowFingerprints;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.RecordingMerchantCategorizer;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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

    @Autowired
    RecordingMerchantCategorizer categorizer;

    private static final String[] ELIGIBLE = {"Cafe Aurora", "Mercado Lua, Lda", "Cinema Plaza", "Late Night Kebab"};

    @Test
    void preview_unknownCardMerchant_getsAiSuggestion() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID mercado = fixtures.category(s, "Mercearia", CategoryKind.EXPENSE);
        categorizer.answerWith(c -> Map.of(DescriptionKeys.of("Mercado Lua, Lda"), mercado));

        JsonNode root = body(preview(s, sample(), bank.toString(), null));

        JsonNode r = row(root, "Mercado Lua, Lda");
        assertThat(r.path("suggestionSource").asString()).isEqualTo("AI");
        assertThat(r.path("suggestedCategoryId").asString()).isEqualTo(mercado.toString());
        assertThat(row(root, "Cafe Aurora").path("suggestionSource").asString()).isNotEqualTo("AI");
    }

    @Test
    void preview_sendsOnlyEligibleMerchants() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        UUID mercado = fixtures.category(s, "Mercearia", CategoryKind.EXPENSE);
        UUID archived = fixtures.category(s, "Velha", CategoryKind.EXPENSE);
        jdbc.update("UPDATE categories SET archived_at = now() WHERE id = ?", archived);
        fixtures.category(s, "Salario", CategoryKind.INCOME);

        preview(s, sample(), bank.toString(), null);

        assertThat(categorizer.calls()).hasSize(1);
        var call = categorizer.calls().get(0);
        assertThat(call.accountId()).isEqualTo(s.accountId());
        var descriptions = call.merchants().stream().map(m -> m.description()).toList();
        assertThat(descriptions).contains(ELIGIBLE);
        assertThat(descriptions)
                .doesNotContain("Top-Up by *1234", "Salary Fictional Corp", "To Savings", "From Current")
                .noneMatch(d -> d.startsWith("Cash withdrawal"));
        assertThat(call.merchants().stream().filter(m -> m.description().equals("Cinema Plaza")))
                .hasSize(1);
        assertThat(call.merchants().get(0).description()).isEqualTo("Cafe Aurora");
        var names = call.categories().stream().map(c -> c.name()).toList();
        assertThat(names).contains("Mercearia").doesNotContain("Outros", "Other", "Velha", "Salario");
        assertThat(call.categories()).extracting(c -> c.id()).contains(mercado);
    }

    @Test
    void preview_aiDisabled_neverCallsCategorizer() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        fixtures.category(s, "Mercearia", CategoryKind.EXPENSE);
        var patch = mvc.patch()
                .uri("/api/v1/account")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("{\"aiSuggestionsEnabled\":false}")
                .exchange();
        assertThat(patch).hasStatus(HttpStatus.OK);

        JsonNode root = body(preview(s, sample(), bank.toString(), null));

        assertThat(categorizer.calls()).isEmpty();
        assertThat(row(root, "Cafe Aurora").path("suggestionSource").asString()).isNotEqualTo("AI");
    }

    @Test
    void preview_categorizerAnswerOutsideList_ignored() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID bank = fixtures.bankAccount(s, "Revolut", 0);
        fixtures.category(s, "Mercearia", CategoryKind.EXPENSE);
        UUID income = fixtures.category(s, "Salario", CategoryKind.INCOME);
        categorizer.answerWith(c -> Map.of(
                DescriptionKeys.of("Cafe Aurora"), income, DescriptionKeys.of("Cinema Plaza"), UUID.randomUUID()));

        JsonNode root = body(preview(s, sample(), bank.toString(), null));

        assertThat(row(root, "Cafe Aurora").path("suggestionSource").asString()).isNotEqualTo("AI");
        assertThat(row(root, "Cafe Aurora").path("suggestedCategoryId").asString())
                .isNotEqualTo(income.toString());
        assertThat(row(root, "Cinema Plaza").path("suggestionSource").asString())
                .isNotEqualTo("AI");
    }
}
