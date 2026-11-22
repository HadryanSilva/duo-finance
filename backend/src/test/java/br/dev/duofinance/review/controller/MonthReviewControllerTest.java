package br.dev.duofinance.review.controller;

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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class MonthReviewControllerTest {

    private static final String BASE = "/api/v1/month-review";
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

    /** Counts every JDBC statement prepared through the DataSource. */
    static final AtomicLong JDBC_STATEMENTS = new AtomicLong();

    @TestConfiguration(proxyBeanMethods = false)
    static class StatementCounting {

        @Bean
        static BeanPostProcessor reviewStatementCountingDataSource() {
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

    /** Mid-March 2027: the last closed month is February. */
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

    private JsonNode review(TestSession s, String query) throws Exception {
        MvcTestResult result = get(s, query);
        assertThat(result).hasStatus(HttpStatus.OK);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void tx(TestSession s, String kind, long cents, String date, UUID category) {
        Map<String, Object> body = new HashMap<>();
        body.put("kind", kind);
        body.put("amountCents", cents);
        body.put("date", date);
        body.put("categoryId", category);
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

    private static JsonNode line(JsonNode d, UUID category) {
        for (JsonNode c : d.path("categories")) {
            if (c.path("categoryId").asString().equals(category.toString())) {
                return c;
            }
        }
        throw new AssertionError("no line for " + category);
    }

    private static void assertCode(MvcTestResult result, String code) throws Exception {
        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        JsonNode body =
                JsonMapper.builder().build().readTree(result.getResponse().getContentAsString());
        assertThat(body.path("code").asString()).isEqualTo(code);
    }

    @Test
    void review_defaultsToLastClosedMonth() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");

        JsonNode d = review(s, "");

        assertThat(d.path("month").asString()).isEqualTo("2027-02");
        assertThat(d.path("lastClosedMonth").asString()).isEqualTo("2027-02");
    }

    /** At 05:00 UTC on the 1st it is still February at UTC-12: February is not closed everywhere yet. */
    @Test
    void review_lastClosedMonthFollowsRunningMonths() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        clock.setInstant(Instant.parse("2027-03-01T05:00:00Z"));

        assertThat(review(s, "").path("lastClosedMonth").asString()).isEqualTo("2027-01");
        assertCode(get(s, "?month=2027-02"), "MONTH_NOT_CLOSED");
    }

    @Test
    void review_runningMonth_isRejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");

        assertCode(get(s, "?month=2027-03"), "MONTH_NOT_CLOSED");
    }

    @Test
    void review_noTransactions_hasNoFirstMonthAndNoComparison() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");

        JsonNode d = review(s, "");

        assertThat(d.path("firstMonth").isNull()).isTrue();
        assertThat(d.path("comparedMonths").asInt()).isZero();
        assertThat(d.path("income").path("cents").asLong()).isZero();
        assertThat(d.path("income").path("averageCents").isNull()).isTrue();
        assertThat(d.path("categories")).isEmpty();
    }

    @Test
    void review_threeMonthAverage_totalsAndCategories() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = seeded(s, "EXPENSE");
        UUID pay = seeded(s, "INCOME");
        String[] months = {"2026-11", "2026-12", "2027-01", "2027-02"};
        long[] spent = {300, 600, 900, 1000};
        for (int i = 0; i < months.length; i++) {
            tx(s, "EXPENSE", spent[i], months[i] + "-10", food);
            tx(s, "INCOME", 5000, months[i] + "-05", pay);
        }

        JsonNode d = review(s, "?month=2027-02");

        assertThat(d.path("firstMonth").asString()).isEqualTo("2026-11");
        assertThat(d.path("comparedMonths").asInt()).isEqualTo(3);
        assertThat(d.path("expense").path("cents").asLong()).isEqualTo(1000);
        assertThat(d.path("expense").path("averageCents").asLong()).isEqualTo(600);
        assertThat(d.path("income").path("cents").asLong()).isEqualTo(5000);
        assertThat(d.path("income").path("averageCents").asLong()).isEqualTo(5000);
        assertThat(d.path("left").path("cents").asLong()).isEqualTo(4000);
        assertThat(d.path("left").path("averageCents").asLong()).isEqualTo(4400);
        JsonNode l = line(d, food);
        assertThat(l.path("cents").asLong()).isEqualTo(1000);
        assertThat(l.path("averageCents").asLong()).isEqualTo(600);
        assertThat(l.path("archived").asBoolean()).isFalse();
    }

    @Test
    void review_averageOnlyCountsMonthsSinceFirst() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = seeded(s, "EXPENSE");
        tx(s, "EXPENSE", 400, "2027-01-10", food);
        tx(s, "EXPENSE", 100, "2027-02-10", food);

        JsonNode d = review(s, "?month=2027-02");

        assertThat(d.path("comparedMonths").asInt()).isEqualTo(1);
        assertThat(d.path("expense").path("averageCents").asLong()).isEqualTo(400);
        assertThat(line(d, food).path("averageCents").asLong()).isEqualTo(400);
    }

    @Test
    void review_firstMonth_hasNoComparison() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID food = seeded(s, "EXPENSE");
        tx(s, "EXPENSE", 400, "2027-01-10", food);

        JsonNode d = review(s, "?month=2027-01");

        assertThat(d.path("comparedMonths").asInt()).isZero();
        assertThat(d.path("expense").path("averageCents").isNull()).isTrue();
        assertThat(d.path("left").path("averageCents").isNull()).isTrue();
        assertThat(line(d, food).path("averageCents").isNull()).isTrue();
    }

    @Test
    void review_monthBeforeFirst_returnsZerosAndNoComparison() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        tx(s, "EXPENSE", 400, "2027-01-10", seeded(s, "EXPENSE"));

        JsonNode d = review(s, "?month=2026-06");

        assertThat(d.path("comparedMonths").asInt()).isZero();
        assertThat(d.path("expense").path("cents").asLong()).isZero();
        assertThat(d.path("categories")).isEmpty();
    }

    /** A category spent only in the window, archived since, still shows up with nothing this month. */
    @Test
    void review_categoryOnlyInWindowAppearsWithZero() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID gifts = fixtures.category(s, "Gifts", CategoryKind.EXPENSE);
        tx(s, "INCOME", 5000, "2026-11-05", seeded(s, "INCOME"));
        tx(s, "EXPENSE", 900, "2026-11-10", gifts);
        jdbc.update("UPDATE categories SET archived_at = now() WHERE id = ?", gifts);

        JsonNode d = review(s, "?month=2027-02");

        JsonNode l = line(d, gifts);
        assertThat(l.path("cents").asLong()).isZero();
        assertThat(l.path("averageCents").asLong()).isEqualTo(300);
        assertThat(l.path("archived").asBoolean()).isTrue();
        assertThat(l.path("name").asString()).isEqualTo("Gifts");
    }

    @Test
    void review_averageRoundsHalfUp() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID a = fixtures.category(s, "A", CategoryKind.EXPENSE);
        UUID b = fixtures.category(s, "B", CategoryKind.EXPENSE);
        UUID pay = seeded(s, "INCOME");
        String[] window = {"2026-11", "2026-12", "2027-01"};
        long[] spentA = {100, 100, 101};
        long[] spentB = {100, 101, 101};
        long[] income = {1000, 1000, 1001};
        for (int i = 0; i < window.length; i++) {
            tx(s, "EXPENSE", spentA[i], window[i] + "-10", a);
            tx(s, "EXPENSE", spentB[i], window[i] + "-11", b);
            tx(s, "INCOME", income[i], window[i] + "-05", pay);
        }

        JsonNode d = review(s, "?month=2027-02");

        assertThat(line(d, a).path("averageCents").asLong()).isEqualTo(100);
        assertThat(line(d, b).path("averageCents").asLong()).isEqualTo(101);
        long incomeAvg = d.path("income").path("averageCents").asLong();
        long expenseAvg = d.path("expense").path("averageCents").asLong();
        assertThat(incomeAvg).isEqualTo(1000); // 3001 / 3 = 1000.33
        assertThat(expenseAvg).isEqualTo(201); // 603 / 3 = 201
        assertThat(d.path("left").path("averageCents").asLong()).isEqualTo(incomeAvg - expenseAvg);
    }

    @Test
    void review_excludesTransfers() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID checking = fixtures.bankAccount(s, "Checking", 0);
        UUID savings = fixtures.bankAccount(s, "Savings", 0);
        tx(s, "EXPENSE", 100, "2027-02-10", seeded(s, "EXPENSE"));
        transfer(s, 5000, "2027-02-11", checking, savings);

        JsonNode d = review(s, "?month=2027-02");

        assertThat(d.path("income").path("cents").asLong()).isZero();
        assertThat(d.path("expense").path("cents").asLong()).isEqualTo(100);
    }

    @Test
    void review_isScopedToHousehold() throws Exception {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession bob = testUsers.verified("bob@mail.com");
        tx(bob, "EXPENSE", 700, "2027-01-10", seeded(bob, "EXPENSE"));
        tx(bob, "EXPENSE", 700, "2027-02-10", seeded(bob, "EXPENSE"));

        JsonNode d = review(ana, "?month=2027-02");

        assertThat(d.path("firstMonth").isNull()).isTrue();
        assertThat(d.path("expense").path("cents").asLong()).isZero();
        assertThat(d.path("categories")).isEmpty();
    }

    @Test
    void review_queryCountIsFixed() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        seedVolume(s, 2);
        review(s, "?month=2027-02"); // warm-up
        long small = statementsFor(s);

        seedVolume(s, 6);
        review(s, "?month=2027-02"); // warm-up
        long large = statementsFor(s);

        // 2 auth/entitlement + 3 for the review: meta (currency, first date), monthly totals, monthly spending.
        assertThat(small).isEqualTo(AUTH_STATEMENTS + 3);
        assertThat(large).isEqualTo(small);
    }

    private long statementsFor(TestSession s) throws Exception {
        JDBC_STATEMENTS.set(0);
        review(s, "?month=2027-02");
        return JDBC_STATEMENTS.get();
    }

    private void seedVolume(TestSession s, int n) {
        String suffix = Long.toString(System.nanoTime());
        for (int i = 0; i < n; i++) {
            UUID category = fixtures.category(s, "C" + i + "-" + suffix, CategoryKind.EXPENSE);
            tx(s, "EXPENSE", 10 + i, "2026-12-%02d".formatted(i + 1), category);
            tx(s, "EXPENSE", 20 + i, "2027-02-%02d".formatted(i + 1), category);
        }
    }
}
