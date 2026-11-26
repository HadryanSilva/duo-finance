package br.dev.duofinance.isolation;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import tools.jackson.databind.json.JsonMapper;

/** Every route that takes an id must treat a resource of another Account as nonexistent. */
@IntegrationTest
class AccountIsolationTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private final JsonMapper json = JsonMapper.builder().build();

    static Stream<Arguments> idRoutes() {
        return Stream.of(
                Arguments.of("bank-accounts", "GET", ""),
                Arguments.of("bank-accounts", "PUT", ""),
                Arguments.of("bank-accounts", "DELETE", ""),
                Arguments.of("bank-accounts", "POST", "/archive"),
                Arguments.of("bank-accounts", "POST", "/unarchive"),
                Arguments.of("categories", "GET", ""),
                Arguments.of("categories", "PUT", ""),
                Arguments.of("categories", "DELETE", ""),
                Arguments.of("categories", "POST", "/archive"),
                Arguments.of("categories", "POST", "/unarchive"),
                Arguments.of("transactions", "GET", ""),
                Arguments.of("transactions", "PUT", ""),
                Arguments.of("transactions", "DELETE", ""),
                Arguments.of("recurring-rules", "GET", ""),
                Arguments.of("recurring-rules", "PUT", ""),
                Arguments.of("recurring-rules", "DELETE", ""),
                Arguments.of("recurring-rules", "POST", "/activate"),
                Arguments.of("recurring-rules", "POST", "/deactivate"),
                Arguments.of("budgets", "GET", ""),
                Arguments.of("budgets", "PUT", ""),
                Arguments.of("budgets", "DELETE", ""),
                Arguments.of("goals", "PUT", ""),
                Arguments.of("goals", "DELETE", ""),
                Arguments.of("goals", "POST", "/complete"),
                Arguments.of("goals", "POST", "/reopen"),
                Arguments.of("goals", "GET", "/movements"),
                Arguments.of("goals", "POST", "/withdrawals"),
                Arguments.of("goals", "POST", "/contributions"),
                Arguments.of("goals", "POST", "/skips"));
    }

    @ParameterizedTest(name = "{1} /{0}/id{2} of another account returns 404")
    @MethodSource("idRoutes")
    void foreignId_returns404(String resource, String method, String suffix) {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession intruder = testUsers.verified("rui@mail.com");
        Map<String, UUID> ids = createAll(owner);
        UUID intruderCategory = fixtures.category(intruder, "Cat Teste C", CategoryKind.EXPENSE);

        var request = mvc.method(HttpMethod.valueOf(method))
                .uri("/api/v1/" + resource + "/" + ids.get(resource) + suffix)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder));
        if (method.equals("PUT")) {
            request.contentType(MediaType.APPLICATION_JSON).content(updateBody(resource, intruderCategory));
        } else if (method.equals("POST") && resource.equals("goals")) {
            request.contentType(MediaType.APPLICATION_JSON).content(goalActionBody(suffix));
        }
        String goalState = resource.equals("goals") ? goalState(ids.get("goals")) : null;
        var result = request.exchange();

        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
        if (goalState != null) {
            assertThat(goalState(ids.get("goals"))).as("nothing written").isEqualTo(goalState);
        }
    }

    /** The goal row and its movements, to prove a rejected request wrote nothing. */
    private String goalState(UUID goal) {
        return jdbc.queryForObject(
                "SELECT g::text || (SELECT count(*) FROM goal_movements WHERE goal_id = g.id) FROM goals g WHERE id = ?",
                String.class,
                goal);
    }

    @Test
    void foreignGoalMovementDelete_returns404AndKeepsMovement() {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession intruder = testUsers.verified("rui@mail.com");
        UUID goal = createAll(owner).get("goals");
        UUID movement =
                jdbc.queryForObject("SELECT id FROM goal_movements WHERE goal_id = ? LIMIT 1", UUID.class, goal);

        var result = mvc.delete()
                .uri("/api/v1/goals/" + goal + "/movements/" + movement)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                .exchange();

        assertNotFound(result);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM goal_movements WHERE id = ?", Long.class, movement))
                .isEqualTo(1);
    }

    @Test
    void goalSettingsWithForeignIncomeCategory_returns404AndKeepsSetting() {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession intruder = testUsers.verified("rui@mail.com");
        UUID foreignIncome = fixtures.category(owner, "Cat Teste I", CategoryKind.INCOME);

        var result = mvc.put()
                .uri("/api/v1/goals/settings")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("salaryCategoryId", foreignIncome)))
                .exchange();

        assertNotFound(result);
        assertThat(jdbc.queryForObject(
                        "SELECT salary_category_id FROM accounts WHERE id = ?", UUID.class, intruder.accountId()))
                .isNull();
    }

    @Test
    void foreignDelete_leavesResourceUntouched() {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession intruder = testUsers.verified("rui@mail.com");
        Map<String, UUID> ids = createAll(owner);

        mvc.delete()
                .uri("/api/v1/transactions/" + ids.get("transactions"))
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                .exchange();

        assertThat(mvc.get()
                        .uri("/api/v1/transactions/" + ids.get("transactions"))
                        .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(owner))
                        .exchange())
                .hasStatus(HttpStatus.OK);
    }

    @Test
    void referencingForeignCategoryOrBankAccount_returns404() {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession intruder = testUsers.verified("rui@mail.com");
        Map<String, UUID> ids = createAll(owner);

        var foreignCategory = mvc.post()
                .uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        100,
                        "date",
                        "2026-11-01",
                        "categoryId",
                        ids.get("categories"))))
                .exchange();
        assertThat(foreignCategory).hasStatus(HttpStatus.NOT_FOUND);

        UUID ownCategory = fixtures.category(intruder, "Cat Teste A", CategoryKind.EXPENSE);
        var foreignBank = mvc.post()
                .uri("/api/v1/transactions")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        100,
                        "date",
                        "2026-11-01",
                        "categoryId",
                        ownCategory,
                        "bankAccountId",
                        ids.get("bank-accounts"))))
                .exchange();
        assertThat(foreignBank).hasStatus(HttpStatus.NOT_FOUND);

        var foreignFilter = mvc.get()
                .uri("/api/v1/transactions?categoryId=" + ids.get("categories"))
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                .exchange();
        assertThat(foreignFilter).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void otherForeignReferences_return404AndWriteNothing() {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession intruder = testUsers.verified("rui@mail.com");
        Map<String, UUID> ids = createAll(owner);
        UUID ownBank = fixtures.bankAccount(intruder, "Conta Rui", 0);
        long before = rowCount();

        assertNotFound(get(intruder, "/api/v1/transactions?bankAccountId=" + ids.get("bank-accounts")));
        assertNotFound(post(
                intruder,
                "/api/v1/transactions",
                Map.of(
                        "kind",
                        "TRANSFER",
                        "amountCents",
                        100,
                        "date",
                        "2026-11-01",
                        "bankAccountId",
                        ownBank,
                        "destinationBankAccountId",
                        ids.get("bank-accounts"))));
        assertNotFound(mvc.post()
                .uri("/api/v1/imports/revolut/preview")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                .multipart()
                .file(new org.springframework.mock.web.MockMultipartFile("file", "s.csv", "text/csv", "x".getBytes()))
                .file(new org.springframework.mock.web.MockMultipartFile(
                        "bankAccountId",
                        "",
                        "text/plain",
                        ids.get("bank-accounts").toString().getBytes()))
                .exchange());
        assertNotFound(post(
                intruder,
                "/api/v1/recurring-rules",
                Map.of(
                        "kind", "EXPENSE",
                        "amountCents", 100,
                        "categoryId", ids.get("categories"),
                        "frequency", "MONTHLY",
                        "startDate", "2099-01-01")));
        assertNotFound(post(
                intruder, "/api/v1/budgets", Map.of("categoryId", ids.get("categories"), "monthlyLimitCents", 100)));
        assertNotFound(post(
                intruder,
                "/api/v1/bank-accounts",
                Map.of("name", "Y", "type", "CHECKING", "initialBalanceCents", 0, "holderUserId", owner.userId())));

        assertThat(rowCount()).isEqualTo(before);
    }

    private long rowCount() {
        return jdbc.queryForObject(
                "SELECT (SELECT count(*) FROM transactions) + (SELECT count(*) FROM recurring_rules)"
                        + " + (SELECT count(*) FROM budgets) + (SELECT count(*) FROM bank_accounts)",
                Long.class);
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult get(TestSession s, String uri) {
        return mvc.get()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();
    }

    private org.springframework.test.web.servlet.assertj.MvcTestResult post(
            TestSession s, String uri, Map<String, Object> body) {
        return mvc.post()
                .uri(uri)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))
                .exchange();
    }

    private static void assertNotFound(org.springframework.test.web.servlet.assertj.MvcTestResult result) {
        assertThat(result).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("NOT_FOUND");
    }

    @Test
    void foreignImportBatchUndo_returns404AndKeepsBatch() {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession intruder = testUsers.verified("rui@mail.com");
        UUID batch = UUID.randomUUID();
        jdbc.update(
                "INSERT INTO import_batches (id, account_id, source, created_by, row_count, created_at)"
                        + " VALUES (?, ?, 'REVOLUT', ?, 0, now())",
                batch,
                owner.accountId(),
                owner.userId());

        var result = mvc.post()
                .uri("/api/v1/imports/" + batch + "/undo")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                .exchange();

        assertNotFound(result);
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM import_batches WHERE id = ? AND undone_at IS NULL", Long.class, batch))
                .isEqualTo(1);
    }

    @Test
    void lists_neverShowOtherAccountsData() throws Exception {
        TestSession owner = testUsers.verified("ana@mail.com");
        TestSession intruder = testUsers.verified("rui@mail.com");
        createAll(owner);

        for (String path : new String[] {"bank-accounts", "transactions", "recurring-rules", "budgets"}) {
            var result = mvc.get()
                    .uri("/api/v1/" + path)
                    .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                    .exchange();
            assertThat(result).hasStatus(HttpStatus.OK);
            var root = json.readTree(result.getResponse().getContentAsString());
            var items = root.has("content") ? root.path("content") : root;
            assertThat(items.size()).as(path).isZero();
        }

        var goals = mvc.get()
                .uri("/api/v1/goals")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(intruder))
                .exchange();
        assertThat(goals).hasStatus(HttpStatus.OK);
        var overview = json.readTree(goals.getResponse().getContentAsString());
        assertThat(overview.path("goals").size()).isZero();
        assertThat(overview.path("completed").size()).isZero();
        assertThat(overview.path("totalSavedCents").asLong()).isZero();
    }

    private Map<String, UUID> createAll(TestSession owner) {
        Map<String, UUID> ids = new HashMap<>();
        UUID expense = fixtures.category(owner, "Cat Teste A", CategoryKind.EXPENSE);
        UUID bank = fixtures.bankAccount(owner, "Conta", 1000);
        ids.put("categories", expense);
        ids.put("bank-accounts", bank);
        ids.put(
                "transactions",
                fixtures.transaction(
                        owner,
                        Map.of(
                                "kind",
                                "EXPENSE",
                                "amountCents",
                                500,
                                "date",
                                "2026-11-01",
                                "categoryId",
                                expense,
                                "bankAccountId",
                                bank)));
        ids.put(
                "recurring-rules",
                create(
                        owner,
                        "recurring-rules",
                        Map.of(
                                "kind",
                                "EXPENSE",
                                "amountCents",
                                700,
                                "categoryId",
                                expense,
                                "bankAccountId",
                                bank,
                                "frequency",
                                "MONTHLY",
                                "startDate",
                                "2099-01-01")));
        UUID budgetCategory = fixtures.category(owner, "Cat Teste B", CategoryKind.EXPENSE);
        ids.put("budgets", create(owner, "budgets", Map.of("categoryId", budgetCategory, "monthlyLimitCents", 9000)));
        UUID goal = create(owner, "goals", Map.of("name", "Trip", "targetCents", 100000, "monthlyPercent", 10));
        ids.put("goals", goal);
        assertThat(mvc.post()
                        .uri("/api/v1/goals/" + goal + "/contributions")
                        .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(owner))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("amountCents", 1000)))
                        .exchange())
                .hasStatus(HttpStatus.CREATED);
        return ids;
    }

    private UUID create(TestSession session, String resource, Map<String, Object> body) {
        var response = mvc.post()
                .uri("/api/v1/" + resource)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(session))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))
                .exchange()
                .getResponse();
        if (response.getStatus() != 201) {
            throw new IllegalStateException(resource + " creation failed: " + response.getStatus());
        }
        try {
            return UUID.fromString(
                    json.readTree(response.getContentAsString()).path("id").asString());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Valid body for each goal POST action, so the request reaches the ownership check. */
    private String goalActionBody(String suffix) {
        Map<String, Object> body = switch (suffix) {
            case "/withdrawals", "/contributions" -> Map.of("amountCents", 100);
            case "/skips" -> Map.of("month", "2026-11");
            default -> Map.of();
        };
        return json.writeValueAsString(body);
    }

    /** Minimal valid body per resource, so a PUT reaches the lookup instead of failing validation. */
    private String updateBody(String resource, UUID intruderCategory) {
        Map<String, Object> body = switch (resource) {
            case "bank-accounts" -> Map.of("name", "X", "type", "CHECKING", "initialBalanceCents", 0);
            case "categories" -> Map.of("name", "X", "color", "#112233", "icon", "tag");
            case "transactions" ->
                Map.of("kind", "EXPENSE", "amountCents", 100, "date", "2026-11-01", "categoryId", intruderCategory);
            case "recurring-rules" -> Map.of("amountCents", 100, "categoryId", intruderCategory);
            case "budgets" -> Map.of("monthlyLimitCents", 100);
            case "goals" -> Map.of("name", "X", "targetCents", 100, "monthlyPercent", 10);
            default -> throw new IllegalArgumentException(resource);
        };
        return json.writeValueAsString(body);
    }
}
