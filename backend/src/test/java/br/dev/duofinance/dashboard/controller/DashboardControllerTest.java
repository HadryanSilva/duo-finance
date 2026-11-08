package br.dev.duofinance.dashboard.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class DashboardControllerTest {

    private static final String BASE = "/api/v1/dashboard";
    private static final long AUTH_STATEMENTS = 2;

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

    /** Counts every JDBC statement prepared through the DataSource: Hibernate's and JdbcTemplate's alike. */
    static final AtomicLong JDBC_STATEMENTS = new AtomicLong();

    @TestConfiguration(proxyBeanMethods = false)
    static class StatementCounting {

        @Bean
        static BeanPostProcessor statementCountingDataSource() {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    return bean instanceof DataSource ds ? countingDataSource(ds) : bean;
                }
            };
        }

        private static DataSource countingDataSource(DataSource ds) {
            return (DataSource) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                        Object result = invoke(ds, method, args);
                        return result instanceof Connection c ? countingConnection(c) : result;
                    });
        }

        private static Connection countingConnection(Connection connection) {
            return (Connection) Proxy.newProxyInstance(
                    DataSource.class.getClassLoader(), new Class<?>[] {Connection.class}, (proxy, method, args) -> {
                        String name = method.getName();
                        if (name.equals("prepareStatement")
                                || name.equals("createStatement")
                                || name.equals("prepareCall")) {
                            JDBC_STATEMENTS.incrementAndGet();
                        }
                        return invoke(connection, method, args);
                    });
        }

        private static Object invoke(Object target, Method method, Object[] args) throws Throwable {
            try {
                return method.invoke(target, args);
            } catch (InvocationTargetException e) {
                throw e.getCause();
            }
        }
    }

    private final JsonMapper json = JsonMapper.builder().build();

    /** Set before any user is registered: tokens live 15 minutes of clock time. */
    @BeforeEach
    void setClock() {
        clock.setInstant(Instant.parse("2027-03-15T10:00:00Z"));
    }

    private MvcTestResult get(TestSession s, String query) {
        return mvc.get()
                .uri(BASE + query)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();
    }

    private JsonNode dashboard(TestSession s, String query) throws Exception {
        MvcTestResult result = get(s, query);
        assertThat(result).hasStatus(HttpStatus.OK);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void tx(TestSession s, String kind, long cents, String date, UUID category, UUID bank) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        body.put("amountCents", cents);
        body.put("date", date);
        body.put("categoryId", category);
        if (bank != null) {
            body.put("bankAccountId", bank);
        }
        fixtures.transaction(s, body);
    }

    private void transfer(TestSession s, long cents, String date, UUID from, UUID to) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "TRANSFER");
        body.put("amountCents", cents);
        body.put("date", date);
        body.put("bankAccountId", from);
        body.put("destinationBankAccountId", to);
        fixtures.transaction(s, body);
    }

    private UUID seeded(TestSession s, String kind) {
        return jdbc.queryForObject(
                "SELECT id FROM categories WHERE account_id = ? AND kind = ? ORDER BY name LIMIT 1",
                UUID.class,
                s.accountId(),
                kind);
    }

    private UUID weeklyRule(TestSession s, String startDate, String endDate) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "EXPENSE");
        body.put("amountCents", 700);
        body.put("description", "Gym");
        body.put("categoryId", seeded(s, "EXPENSE"));
        body.put("frequency", "WEEKLY");
        body.put("startDate", startDate);
        if (endDate != null) {
            body.put("endDate", endDate);
        }
        MvcTestResult result = mvc.post()
                .uri("/api/v1/recurring-rules")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .path("id")
                .asString());
    }

    private void budget(TestSession s, UUID category, long limit) {
        MvcTestResult result = mvc.post()
                .uri("/api/v1/budgets")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("categoryId", category, "monthlyLimitCents", limit)))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
    }

    private static List<String> dates(JsonNode upcoming) {
        return upcoming.valueStream().map(n -> n.path("date").asString()).toList();
    }

    @Test
    void dashboard_currentMonth_aggregatesEverything() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = fixtures.category(s, "Food", CategoryKind.EXPENSE);
        UUID fun = fixtures.category(s, "Fun", CategoryKind.EXPENSE);
        UUID pay = fixtures.category(s, "Pay", CategoryKind.INCOME);
        UUID checking = fixtures.bankAccount(s, "B-Checking", 10_000);
        UUID savings = fixtures.bankAccount(s, "A-Savings", 500);
        tx(s, "INCOME", 50_000, "2027-03-10", pay, checking);
        tx(s, "EXPENSE", 2_000, "2027-03-12", food, checking);
        tx(s, "EXPENSE", 3_000, "2027-03-20", fun, checking); // later this month: in totals, not in balance
        tx(s, "EXPENSE", 9_999, "2027-02-20", food, null); // other month
        transfer(s, 1_000, "2027-03-14", checking, savings);
        budget(s, food, 2_500);

        JsonNode d = dashboard(s, "");

        assertThat(d.path("month").asString()).isEqualTo("2027-03");
        assertThat(d.path("currency").asString()).isEqualTo("EUR");
        assertThat(d.path("balancesAsOf").asString()).isEqualTo("2027-03-15");
        assertThat(d.path("accounts").size()).isEqualTo(2);
        assertThat(d.path("accounts").get(0).path("name").asString()).isEqualTo("A-Savings");
        assertThat(d.path("accounts").get(0).path("balanceCents").asLong()).isEqualTo(1_500);
        assertThat(d.path("accounts").get(0).path("type").asString()).isEqualTo("CHECKING");
        assertThat(d.path("accounts").get(1).path("balanceCents").asLong()).isEqualTo(10_000 + 50_000 - 2_000 - 1_000);
        assertThat(d.path("totalBalanceCents").asLong()).isEqualTo(1_500 + 57_000);
        assertThat(d.path("incomeCents").asLong()).isEqualTo(50_000);
        assertThat(d.path("expenseCents").asLong()).isEqualTo(5_000);
        JsonNode spending = d.path("spendingByCategory");
        assertThat(spending.size()).isEqualTo(2);
        assertThat(spending.get(0).path("name").asString()).isEqualTo("Fun");
        assertThat(spending.get(0).path("totalCents").asLong()).isEqualTo(3_000);
        assertThat(spending.get(0).path("categoryId").asString()).isEqualTo(fun.toString());
        assertThat(spending.get(0).path("color").asString()).isEqualTo("#112233");
        assertThat(spending.get(0).path("icon").asString()).isEqualTo("tag");
        assertThat(spending.get(1).path("name").asString()).isEqualTo("Food");
        assertThat(spending.get(1).path("totalCents").asLong()).isEqualTo(2_000);
        JsonNode budgets = d.path("budgets");
        assertThat(budgets.size()).isEqualTo(1);
        assertThat(budgets.get(0).path("spentCents").asLong()).isEqualTo(2_000);
        assertThat(budgets.get(0).path("percent").asInt()).isEqualTo(80);
        assertThat(budgets.get(0).path("status").asString()).isEqualTo("WARNING");
        assertThat(budgets.get(0).path("month").asString()).isEqualTo("2027-03");
        assertThat(d.path("upcoming").size()).isZero();
    }

    @Test
    void dashboard_pastMonth_balancesAsOfMonthEnd() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = fixtures.category(s, "Food", CategoryKind.EXPENSE);
        UUID bank = fixtures.bankAccount(s, "Main", 1_000);
        tx(s, "EXPENSE", 100, "2027-02-10", food, bank);
        tx(s, "EXPENSE", 400, "2027-03-02", food, bank); // after the end of February

        JsonNode d = dashboard(s, "?month=2027-02");

        assertThat(d.path("month").asString()).isEqualTo("2027-02");
        assertThat(d.path("balancesAsOf").asString()).isEqualTo("2027-02-28");
        assertThat(d.path("totalBalanceCents").asLong()).isEqualTo(900);
        assertThat(d.path("expenseCents").asLong()).isEqualTo(100);

        // A future month is capped at today.
        JsonNode future = dashboard(s, "?month=2027-06");
        assertThat(future.path("balancesAsOf").asString()).isEqualTo("2027-03-15");
        assertThat(future.path("totalBalanceCents").asLong()).isEqualTo(500);
    }

    @Test
    void dashboard_excludesTransfersFromTotalsAndArchivedAccountsFromBalances() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = fixtures.category(s, "Food", CategoryKind.EXPENSE);
        UUID a = fixtures.bankAccount(s, "A", 1_000);
        UUID b = fixtures.bankAccount(s, "B", 2_000);
        transfer(s, 300, "2027-03-05", a, b);
        tx(s, "EXPENSE", 50, "2027-03-06", food, b);
        assertThat(mvc.post()
                        .uri("/api/v1/bank-accounts/{id}/archive", b)
                        .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                        .exchange())
                .hasStatus(HttpStatus.OK);

        JsonNode d = dashboard(s, "");

        assertThat(d.path("incomeCents").asLong()).isZero();
        assertThat(d.path("expenseCents").asLong()).isEqualTo(50);
        assertThat(d.path("spendingByCategory").size()).isEqualTo(1);
        assertThat(d.path("spendingByCategory").get(0).path("totalCents").asLong())
                .isEqualTo(50);
        assertThat(d.path("accounts").size()).isEqualTo(1);
        assertThat(d.path("accounts").get(0).path("id").asString()).isEqualTo(a.toString());
        assertThat(d.path("totalBalanceCents").asLong()).isEqualTo(700);
    }

    @Test
    void dashboard_spendingIncludesArchivedCategory() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID old = fixtures.category(s, "Old", CategoryKind.EXPENSE);
        tx(s, "EXPENSE", 123, "2027-03-02", old, null);
        assertThat(mvc.post()
                        .uri("/api/v1/categories/{id}/archive", old)
                        .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                        .exchange())
                .hasStatus(HttpStatus.OK);

        JsonNode spending = dashboard(s, "").path("spendingByCategory");

        assertThat(spending.size()).isEqualTo(1);
        assertThat(spending.get(0).path("name").asString()).isEqualTo("Old");
        assertThat(spending.get(0).path("totalCents").asLong()).isEqualTo(123);
    }

    @Test
    void dashboard_upcomingNext30Days() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID weekly = weeklyRule(s, "2027-03-20", null); // 20, 27 Mar, 3, 10 Apr; window ends 14 Apr
        UUID bounded = weeklyRule(s, "2027-03-16", "2027-03-25"); // 16, 23 Mar
        UUID inactive = weeklyRule(s, "2027-03-17", null);
        jdbc.update("UPDATE recurring_rules SET active = false WHERE id = ?", inactive);

        JsonNode upcoming = dashboard(s, "?month=2026-01").path("upcoming"); // independent of month

        assertThat(dates(upcoming))
                .containsExactly("2027-03-16", "2027-03-20", "2027-03-23", "2027-03-27", "2027-04-03", "2027-04-10");
        assertThat(upcoming.get(0).path("ruleId").asString()).isEqualTo(bounded.toString());
        assertThat(upcoming.get(1).path("ruleId").asString()).isEqualTo(weekly.toString());
        assertThat(upcoming.get(1).path("kind").asString()).isEqualTo("EXPENSE");
        assertThat(upcoming.get(1).path("amountCents").asLong()).isEqualTo(700);
        assertThat(upcoming.get(1).path("description").asString()).isEqualTo("Gym");
        assertThat(upcoming.get(1).path("categoryId").asString())
                .isEqualTo(seeded(s, "EXPENSE").toString());
    }

    @Test
    void dashboard_upcomingStartsAtTodayAndIsLimitedTo20() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        // Started in the past: creation generates up to today, so the next run is 2027-03-22.
        weeklyRule(s, "2027-03-01", null);
        for (int i = 0; i < 6; i++) {
            weeklyRule(s, "2027-03-16", null);
        }

        JsonNode upcoming = dashboard(s, "").path("upcoming");

        assertThat(upcoming.size()).isEqualTo(20);
        List<String> dates = dates(upcoming);
        assertThat(dates).isSorted();
        assertThat(dates.get(0)).isEqualTo("2027-03-16");
        assertThat(dates).allSatisfy(date -> assertThat(date).isGreaterThanOrEqualTo("2027-03-15"));
        // Within one date, ordered by rule id.
        List<String> ids = upcoming.valueStream()
                .filter(n -> n.path("date").asString().equals("2027-03-16"))
                .map(n -> n.path("ruleId").asString())
                .toList();
        assertThat(ids).hasSize(6).isSorted();
    }

    @Test
    void dashboard_invalidMonth_returns400() {
        TestSession s = testUsers.verified("ana@mail.com");

        assertThat(get(s, "?month=2026-13")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(s, "?month=nope")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void dashboard_emptyAccount_returnsZeros() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");

        JsonNode d = dashboard(s, "");

        assertThat(d.path("currency").asString()).isEqualTo("EUR");
        assertThat(d.path("totalBalanceCents").asLong()).isZero();
        assertThat(d.path("incomeCents").asLong()).isZero();
        assertThat(d.path("expenseCents").asLong()).isZero();
        assertThat(d.path("accounts").size()).isZero();
        assertThat(d.path("spendingByCategory").size()).isZero();
        assertThat(d.path("budgets").size()).isZero();
        assertThat(d.path("upcoming").size()).isZero();
    }

    @Test
    void dashboard_otherAccountsDataIsNotIncluded() throws Exception {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession bia = testUsers.verified("bia@mail.com");
        UUID food = fixtures.category(bia, "Food", CategoryKind.EXPENSE);
        UUID bank = fixtures.bankAccount(bia, "Main", 5_000);
        tx(bia, "EXPENSE", 100, "2027-03-02", food, bank);
        budget(bia, food, 1_000);
        weeklyRule(bia, "2027-03-16", null);

        JsonNode d = dashboard(ana, "");

        assertThat(d.path("totalBalanceCents").asLong()).isZero();
        assertThat(d.path("expenseCents").asLong()).isZero();
        assertThat(d.path("budgets").size()).isZero();
        assertThat(d.path("upcoming").size()).isZero();
    }

    @Test
    void dashboard_queryCountIsFixedAndIndependentOfData() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        seedVolume(s, 2);
        dashboard(s, ""); // warm-up
        long small = statementsFor(s);

        seedVolume(s, 6);
        dashboard(s, ""); // warm-up
        long large = statementsFor(s);

        // 2 auth/entitlement + 8 for the dashboard (the brief's bound): totals with currency, bank accounts, balances,
        // spending, budgets, budget categories, spending categories, active rules.
        assertThat(small).isEqualTo(AUTH_STATEMENTS + 8);
        assertThat(large).isEqualTo(small);
    }

    private long statementsFor(TestSession s) throws Exception {
        JDBC_STATEMENTS.set(0);
        dashboard(s, "");
        return JDBC_STATEMENTS.get();
    }

    /** Adds {@code n} categories with spending and a budget, bank accounts, transactions and rules. */
    private void seedVolume(TestSession s, int n) throws Exception {
        String suffix = Long.toString(System.nanoTime());
        UUID pay = fixtures.category(s, "Pay" + suffix, CategoryKind.INCOME);
        for (int i = 0; i < n; i++) {
            UUID category = fixtures.category(s, "C" + i + "-" + suffix, CategoryKind.EXPENSE);
            UUID bank = fixtures.bankAccount(s, "B" + i + "-" + suffix, 100);
            tx(s, "EXPENSE", 10 + i, "2027-03-%02d".formatted(i + 1), category, bank);
            tx(s, "INCOME", 20 + i, "2027-03-%02d".formatted(i + 1), pay, bank);
            budget(s, category, 1_000);
            weeklyRule(s, "2027-03-16", null);
        }
    }
}
