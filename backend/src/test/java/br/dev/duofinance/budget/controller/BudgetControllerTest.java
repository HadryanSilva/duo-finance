package br.dev.duofinance.budget.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import jakarta.persistence.EntityManagerFactory;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class BudgetControllerTest {

    private static final String BASE = "/api/v1/budgets";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    EntityManagerFactory entityManagerFactory;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setClock() {
        clock.setInstant(Instant.parse("2027-03-15T10:00:00Z"));
    }

    private MvcTestResult send(HttpMethod method, TestSession s, String uri, Map<String, Object> body) {
        var builder = mvc.method(method).uri(uri).header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s));
        if (body != null) {
            builder = builder.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return builder.exchange();
    }

    private JsonNode body(MvcTestResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private UUID budget(TestSession s, UUID category, long limit) throws Exception {
        MvcTestResult result =
                send(HttpMethod.POST, s, BASE, Map.of("categoryId", category, "monthlyLimitCents", limit));
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(body(result).path("id").asString());
    }

    private void spend(TestSession s, UUID category, long cents, String date) {
        fixtures.transaction(s, Map.of("kind", "EXPENSE", "amountCents", cents, "date", date, "categoryId", category));
    }

    private JsonNode only(TestSession s, String query) throws Exception {
        MvcTestResult result = send(HttpMethod.GET, s, BASE + query, null);
        assertThat(result).hasStatus(HttpStatus.OK);
        JsonNode list = body(result);
        assertThat(list.size()).isEqualTo(1);
        return list.get(0);
    }

    @Test
    void create_andListWithProgress() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = fixtures.category(s, "Food", CategoryKind.EXPENSE);

        MvcTestResult created = send(HttpMethod.POST, s, BASE, Map.of("categoryId", food, "monthlyLimitCents", 10000));
        assertThat(created).hasStatus(HttpStatus.CREATED);
        assertThat(body(created).path("spentCents").asLong()).isZero();
        assertThat(body(created).path("percent").asInt()).isZero();
        assertThat(body(created).path("status").asString()).isEqualTo("NORMAL");
        assertThat(body(created).path("month").asString()).isEqualTo("2027-03");

        spend(s, food, 4000, "2027-03-02");
        spend(s, food, 3900, "2027-03-31");

        JsonNode item = only(s, "");
        assertThat(item.path("category").path("id").asString()).isEqualTo(food.toString());
        assertThat(item.path("category").path("name").asString()).isEqualTo("Food");
        assertThat(item.path("category").path("archived").asBoolean()).isFalse();
        assertThat(item.path("monthlyLimitCents").asLong()).isEqualTo(10000);
        assertThat(item.path("month").asString()).isEqualTo("2027-03");
        assertThat(item.path("spentCents").asLong()).isEqualTo(7900);
        assertThat(item.path("percent").asInt()).isEqualTo(79);
        assertThat(item.path("status").asString()).isEqualTo("NORMAL");

        JsonNode single =
                body(send(HttpMethod.GET, s, BASE + "/" + item.path("id").asString(), null));
        assertThat(single.path("percent").asInt()).isEqualTo(79);
    }

    @Test
    void status_warningAt80_exceededAt100() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID warn = fixtures.category(s, "Warn", CategoryKind.EXPENSE);
        UUID exact = fixtures.category(s, "Exact", CategoryKind.EXPENSE);
        UUID over = fixtures.category(s, "Over", CategoryKind.EXPENSE);
        budget(s, warn, 10000);
        budget(s, exact, 10000);
        budget(s, over, 10000);
        spend(s, warn, 8000, "2027-03-05");
        spend(s, exact, 10000, "2027-03-05");
        spend(s, over, 15000, "2027-03-05");

        JsonNode list = body(send(HttpMethod.GET, s, BASE, null));
        assertThat(list.size()).isEqualTo(3);
        // Ordered by category name: Exact, Over, Warn.
        assertThat(list.get(0).path("status").asString()).isEqualTo("EXCEEDED");
        assertThat(list.get(0).path("percent").asInt()).isEqualTo(100);
        assertThat(list.get(1).path("status").asString()).isEqualTo("EXCEEDED");
        assertThat(list.get(1).path("percent").asInt()).isEqualTo(150);
        assertThat(list.get(2).path("status").asString()).isEqualTo("WARNING");
        assertThat(list.get(2).path("percent").asInt()).isEqualTo(80);
    }

    @Test
    void progress_floorsPercent() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = fixtures.category(s, "Food", CategoryKind.EXPENSE);
        budget(s, food, 3);
        spend(s, food, 2, "2027-03-05");

        assertThat(only(s, "").path("percent").asInt()).isEqualTo(66);
    }

    @Test
    void progress_ignoresOtherMonthsIncomeAndTransfers() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = fixtures.category(s, "Food", CategoryKind.EXPENSE);
        UUID salary = fixtures.category(s, "Pay", CategoryKind.INCOME);
        UUID a = fixtures.bankAccount(s, "A", 0);
        UUID b = fixtures.bankAccount(s, "B", 0);
        budget(s, food, 10000);
        spend(s, food, 1000, "2027-03-01");
        spend(s, food, 2000, "2027-03-31"); // last day, future relative to the 15th: still counts
        spend(s, food, 500, "2027-02-28");
        spend(s, food, 700, "2027-04-01");
        fixtures.transaction(
                s, Map.of("kind", "INCOME", "amountCents", 9000, "date", "2027-03-10", "categoryId", salary));
        Map<String, Object> transfer = new HashMap<>();
        transfer.put("kind", "TRANSFER");
        transfer.put("amountCents", 8000);
        transfer.put("date", "2027-03-10");
        transfer.put("bankAccountId", a);
        transfer.put("destinationBankAccountId", b);
        fixtures.transaction(s, transfer);

        assertThat(only(s, "").path("spentCents").asLong()).isEqualTo(3000);
        assertThat(only(s, "?month=2027-02").path("spentCents").asLong()).isEqualTo(500);
        assertThat(only(s, "?month=2027-04").path("spentCents").asLong()).isEqualTo(700);
        assertThat(only(s, "?month=2027-02").path("month").asString()).isEqualTo("2027-02");
        assertThat(only(s, "?month=2030-01").path("spentCents").asLong()).isZero();
    }

    @Test
    void list_isNameOrderedIgnoringCase_andKeepsArchivedCategories() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID zeta = fixtures.category(s, "zeta", CategoryKind.EXPENSE);
        UUID alfa = fixtures.category(s, "Alfa", CategoryKind.EXPENSE);
        UUID mid = fixtures.category(s, "beta", CategoryKind.EXPENSE);
        budget(s, zeta, 100);
        budget(s, alfa, 100);
        budget(s, mid, 100);
        assertThat(send(HttpMethod.POST, s, "/api/v1/categories/" + mid + "/archive", null))
                .hasStatus(HttpStatus.OK);

        JsonNode list = body(send(HttpMethod.GET, s, BASE, null));

        assertThat(list.get(0).path("category").path("name").asString()).isEqualTo("Alfa");
        assertThat(list.get(1).path("category").path("name").asString()).isEqualTo("beta");
        assertThat(list.get(1).path("category").path("archived").asBoolean()).isTrue();
        assertThat(list.get(2).path("category").path("name").asString()).isEqualTo("zeta");
    }

    @Test
    void list_statementCountIsIndependentOfBudgets() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        for (int i = 0; i < 2; i++) {
            UUID c = fixtures.category(s, "C" + i, CategoryKind.EXPENSE);
            budget(s, c, 1000);
            spend(s, c, 100, "2027-03-05");
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            send(HttpMethod.GET, s, BASE, null); // warm-up
            statistics.clear();
            assertThat(send(HttpMethod.GET, s, BASE, null)).hasStatus(HttpStatus.OK);
            long few = statistics.getPrepareStatementCount();

            for (int i = 2; i < 6; i++) {
                UUID c = fixtures.category(s, "C" + i, CategoryKind.EXPENSE);
                budget(s, c, 1000);
                spend(s, c, 100, "2027-03-05");
            }
            statistics.clear();
            assertThat(body(send(HttpMethod.GET, s, BASE, null)).size()).isEqualTo(6);
            long many = statistics.getPrepareStatementCount();

            assertThat(many).isEqualTo(few);
            // Measured: 2 auth/entitlement + budgets + categories (the aggregate runs through JDBC).
            assertThat(few).isEqualTo(4);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    @Test
    void put_changesOnlyTheLimit() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = fixtures.category(s, "Food", CategoryKind.EXPENSE);
        UUID other = fixtures.category(s, "Other", CategoryKind.EXPENSE);
        UUID id = budget(s, food, 10000);
        spend(s, food, 5000, "2027-03-05");

        MvcTestResult updated =
                send(HttpMethod.PUT, s, BASE + "/" + id, Map.of("categoryId", other, "monthlyLimitCents", 5000));

        assertThat(updated).hasStatus(HttpStatus.OK);
        assertThat(body(updated).path("monthlyLimitCents").asLong()).isEqualTo(5000);
        assertThat(body(updated).path("category").path("id").asString()).isEqualTo(food.toString());
        assertThat(body(updated).path("percent").asInt()).isEqualTo(100);
        assertThat(body(updated).path("status").asString()).isEqualTo("EXCEEDED");
        assertThat(send(HttpMethod.PUT, s, BASE + "/" + id, Map.of("monthlyLimitCents", 0)))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void create_validatesLimit() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = fixtures.category(s, "Food", CategoryKind.EXPENSE);

        assertThat(send(HttpMethod.POST, s, BASE, Map.of("categoryId", food, "monthlyLimitCents", 0)))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(send(HttpMethod.POST, s, BASE, Map.of("categoryId", food, "monthlyLimitCents", 100_000_000_001L)))
                .hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(send(HttpMethod.POST, s, BASE, Map.of("monthlyLimitCents", 100)))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void incomeCategory_returns400BudgetCategoryInvalid() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID salary = fixtures.category(s, "Pay", CategoryKind.INCOME);

        var result = send(HttpMethod.POST, s, BASE, Map.of("categoryId", salary, "monthlyLimitCents", 100));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("BUDGET_CATEGORY_INVALID");
    }

    @Test
    void archivedCategory_returns409CategoryArchived() {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = fixtures.category(s, "Food", CategoryKind.EXPENSE);
        send(HttpMethod.POST, s, "/api/v1/categories/" + food + "/archive", null);

        var result = send(HttpMethod.POST, s, BASE, Map.of("categoryId", food, "monthlyLimitCents", 100));

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("CATEGORY_ARCHIVED");
    }

    @Test
    void duplicateCategory_returns409BudgetExists() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = fixtures.category(s, "Food", CategoryKind.EXPENSE);
        budget(s, food, 100);

        var result = send(HttpMethod.POST, s, BASE, Map.of("categoryId", food, "monthlyLimitCents", 200));

        assertThat(result).hasStatus(HttpStatus.CONFLICT);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("BUDGET_EXISTS");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM budgets", Long.class))
                .isEqualTo(1L);
    }

    @Test
    void deletingCategory_cascadesBudget() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = fixtures.category(s, "Food", CategoryKind.EXPENSE);
        budget(s, food, 100);

        assertThat(send(HttpMethod.DELETE, s, "/api/v1/categories/" + food, null))
                .hasStatus(HttpStatus.NO_CONTENT);

        assertThat(body(send(HttpMethod.GET, s, BASE, null)).size()).isZero();
    }

    @Test
    void delete_returns204AndRemovesBudget() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = fixtures.category(s, "Food", CategoryKind.EXPENSE);
        UUID id = budget(s, food, 100);

        assertThat(send(HttpMethod.DELETE, s, BASE + "/" + id, null)).hasStatus(HttpStatus.NO_CONTENT);
        assertThat(send(HttpMethod.GET, s, BASE + "/" + id, null)).hasStatus(HttpStatus.NOT_FOUND);
        // The category is free for a new budget again.
        budget(s, food, 100);
    }

    @Test
    void otherAccountsBudget_returns404() throws Exception {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession carl = testUsers.verified("carl@mail.com");
        UUID food = fixtures.category(ana, "Food", CategoryKind.EXPENSE);
        UUID id = budget(ana, food, 100);

        assertThat(send(HttpMethod.GET, carl, BASE + "/" + id, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.PUT, carl, BASE + "/" + id, Map.of("monthlyLimitCents", 5)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(send(HttpMethod.DELETE, carl, BASE + "/" + id, null)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(body(send(HttpMethod.GET, carl, BASE, null)).size()).isZero();
        assertThat(send(HttpMethod.POST, carl, BASE, Map.of("categoryId", food, "monthlyLimitCents", 100)))
                .hasStatus(HttpStatus.NOT_FOUND);
        assertThat(jdbc.queryForObject("SELECT monthly_limit_cents FROM budgets WHERE id = ?", Long.class, id))
                .isEqualTo(100L);
    }

    @Test
    void invalidMonth_returns400ValidationFailed() {
        TestSession s = testUsers.verified("ana@mail.com");

        var result = send(HttpMethod.GET, s, BASE + "?month=2027-13", null);

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(send(HttpMethod.GET, s, BASE + "?month=march", null)).hasStatus(HttpStatus.BAD_REQUEST);
    }
}
