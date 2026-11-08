package br.dev.duofinance.transaction.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class TransactionListTest {

    private static final String BASE = "/api/v1/transactions";

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

    private MvcTestResult get(TestSession s, String query) {
        return mvc.get()
                .uri(BASE + query)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();
    }

    private MvcTestResult search(TestSession s, String q) {
        return mvc.get()
                .uri(BASE + "?q={q}", q)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();
    }

    private JsonNode body(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus(HttpStatus.OK);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private List<String> descriptions(MvcTestResult result) throws Exception {
        return body(result)
                .path("content")
                .valueStream()
                .map(n -> n.path("description").asString())
                .toList();
    }

    private UUID seeded(TestSession s, String kind) {
        return jdbc.queryForObject(
                "SELECT id FROM categories WHERE account_id = ? AND kind = ? LIMIT 1", UUID.class, s.accountId(), kind);
    }

    private UUID expense(TestSession s, String date, String description, UUID category, UUID bank) {
        Map<String, Object> request = new HashMap<>();
        request.put("kind", "EXPENSE");
        request.put("amountCents", 100);
        request.put("date", date);
        if (description != null) {
            request.put("description", description);
        }
        request.put("categoryId", category);
        if (bank != null) {
            request.put("bankAccountId", bank);
        }
        clock.advance(Duration.ofSeconds(1));
        return fixtures.transaction(s, request);
    }

    private UUID expense(TestSession s, String date, String description) {
        return expense(s, date, description, seeded(s, "EXPENSE"), null);
    }

    @Test
    void list_ordersByDateDescThenNewest() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        expense(s, "2026-11-01", "old");
        expense(s, "2026-11-05", "first-on-5th");
        expense(s, "2026-11-05", "second-on-5th");

        assertThat(descriptions(get(s, ""))).containsExactly("second-on-5th", "first-on-5th", "old");
    }

    @Test
    void list_filtersByDateRangeInclusive() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        expense(s, "2026-11-01", "before");
        expense(s, "2026-11-02", "start");
        expense(s, "2026-11-04", "end");
        expense(s, "2026-11-05", "after");

        assertThat(descriptions(get(s, "?from=2026-11-02&to=2026-11-04"))).containsExactly("end", "start");
        assertThat(descriptions(get(s, "?from=2026-11-04"))).containsExactly("after", "end");
        assertThat(descriptions(get(s, "?to=2026-11-01"))).containsExactly("before");
    }

    @Test
    void list_filtersByKindCategoryAndAuthor() throws Exception {
        TestSession[] couple = testUsers.couple("ana@mail.com", "bia@mail.com");
        TestSession ana = couple[0];
        TestSession bia = couple[1];
        UUID food = fixtures.category(ana, "Food", CategoryKind.EXPENSE);
        UUID fun = fixtures.category(ana, "Fun", CategoryKind.EXPENSE);
        UUID salary = fixtures.category(ana, "Pay", CategoryKind.INCOME);
        expense(ana, "2026-11-01", "ana-food", food, null);
        expense(bia, "2026-11-02", "bia-fun", fun, null);
        Map<String, Object> income = new HashMap<>();
        income.put("kind", "INCOME");
        income.put("amountCents", 900);
        income.put("date", "2026-11-03");
        income.put("description", "pay");
        income.put("categoryId", salary);
        fixtures.transaction(bia, income);

        assertThat(descriptions(get(ana, "?kind=INCOME"))).containsExactly("pay");
        assertThat(descriptions(get(ana, "?kind=EXPENSE"))).containsExactly("bia-fun", "ana-food");
        assertThat(descriptions(get(ana, "?categoryId=" + food))).containsExactly("ana-food");
        assertThat(descriptions(get(ana, "?createdBy=" + bia.userId()))).containsExactly("pay", "bia-fun");
        assertThat(descriptions(get(ana, "?createdBy=" + bia.userId() + "&kind=EXPENSE")))
                .containsExactly("bia-fun");
    }

    @Test
    void list_bankAccountMatchesOriginOrDestination() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID a = fixtures.bankAccount(s, "A", 0);
        UUID b = fixtures.bankAccount(s, "B", 0);
        UUID c = fixtures.bankAccount(s, "C", 0);
        UUID category = seeded(s, "EXPENSE");
        expense(s, "2026-11-01", "on-a", category, a);
        expense(s, "2026-11-02", "on-c", category, c);
        Map<String, Object> transfer = new HashMap<>();
        transfer.put("kind", "TRANSFER");
        transfer.put("amountCents", 500);
        transfer.put("date", "2026-11-03");
        transfer.put("description", "a-to-b");
        transfer.put("bankAccountId", a);
        transfer.put("destinationBankAccountId", b);
        fixtures.transaction(s, transfer);

        assertThat(descriptions(get(s, "?bankAccountId=" + a))).containsExactly("a-to-b", "on-a");
        assertThat(descriptions(get(s, "?bankAccountId=" + b))).containsExactly("a-to-b");
    }

    @Test
    void list_qIsLiteralAndCaseInsensitive() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        expense(s, "2026-11-01", "Pizza 50%");
        expense(s, "2026-11-02", "pizza");
        expense(s, "2026-11-03", "50 euros");
        expense(s, "2026-11-04", null);

        assertThat(descriptions(search(s, "50%"))).containsExactly("Pizza 50%");
        assertThat(descriptions(search(s, "PIZZA"))).containsExactly("pizza", "Pizza 50%");
        assertThat(descriptions(search(s, "_"))).isEmpty();
        assertThat(descriptions(search(s, "\\"))).isEmpty();
        assertThat(descriptions(search(s, "%"))).containsExactly("Pizza 50%");
        assertThat(body(search(s, "  ")).path("totalElements").asLong()).isEqualTo(4);
        assertThat(body(get(s, "")).path("totalElements").asLong()).isEqualTo(4);
    }

    @Test
    void list_qOver100Chars_returns400() {
        TestSession s = testUsers.verified("ana@mail.com");

        var result = get(s, "?q=" + "a".repeat(101));

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("q");
        assertThat(result).bodyJson().extractingPath("$.errors[0].code").isEqualTo("Size");
        assertThat(get(s, "?q=" + "a".repeat(100))).hasStatus(HttpStatus.OK);
    }

    @Test
    void list_paginates() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        for (int i = 1; i <= 7; i++) {
            expense(s, "2026-11-0" + i, "t" + i);
        }

        JsonNode last = body(get(s, "?size=3&page=2"));
        assertThat(last.path("totalElements").asLong()).isEqualTo(7);
        assertThat(last.path("totalPages").asInt()).isEqualTo(3);
        assertThat(last.path("page").asInt()).isEqualTo(2);
        assertThat(last.path("content").size()).isEqualTo(1);
        assertThat(descriptions(get(s, "?size=3&page=0"))).containsExactly("t7", "t6", "t5");
        assertThat(body(get(s, "")).path("size").asInt()).isEqualTo(50);
        assertThat(body(get(s, "?size=1000")).path("size").asInt()).isEqualTo(100);
    }

    @Test
    void list_neverShowsOtherAccounts() throws Exception {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession bob = testUsers.verified("bob@mail.com");
        UUID anaCategory = seeded(ana, "EXPENSE");
        UUID anaBank = fixtures.bankAccount(ana, "Main", 0);
        expense(ana, "2026-11-01", "ana-only", anaCategory, anaBank);
        expense(bob, "2026-11-01", "bob-only");

        assertThat(descriptions(get(bob, ""))).containsExactly("bob-only");
        assertThat(descriptions(get(bob, "?categoryId=" + anaCategory))).isEmpty();
        assertThat(descriptions(get(bob, "?bankAccountId=" + anaBank))).isEmpty();
        assertThat(descriptions(get(bob, "?createdBy=" + ana.userId()))).isEmpty();
    }

    @Test
    void list_fromAfterTo_returns400() {
        TestSession s = testUsers.verified("ana@mail.com");

        var result = get(s, "?from=2026-11-05&to=2026-11-04");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.code").isEqualTo("VALIDATION_FAILED");
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("from");
        assertThat(result).bodyJson().extractingPath("$.errors[0].code").isEqualTo("OutOfRange");
    }

    @Test
    void list_badEnumOrUuid_returns400() {
        TestSession s = testUsers.verified("ana@mail.com");

        assertThat(get(s, "?kind=FOO")).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(get(s, "?categoryId=not-a-uuid")).hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void list_queryCountIsFixedAndIndependentOfRows() throws Exception {
        TestSession[] couple = testUsers.couple("ana@mail.com", "bia@mail.com");
        List<UUID> categories = List.of(
                fixtures.category(couple[0], "C1", CategoryKind.EXPENSE),
                fixtures.category(couple[0], "C2", CategoryKind.EXPENSE),
                fixtures.category(couple[0], "C3", CategoryKind.EXPENSE),
                fixtures.category(couple[0], "C4", CategoryKind.EXPENSE));
        List<UUID> banks = List.of(
                fixtures.bankAccount(couple[0], "B1", 0),
                fixtures.bankAccount(couple[0], "B2", 0),
                fixtures.bankAccount(couple[0], "B3", 0));
        for (int i = 1; i <= 12; i++) {
            TestSession author = couple[i % 2];
            String date = "2026-11-%02d".formatted(i);
            if (i % 3 == 0) {
                Map<String, Object> transfer = new HashMap<>();
                transfer.put("kind", "TRANSFER");
                transfer.put("amountCents", 100);
                transfer.put("date", date);
                transfer.put("bankAccountId", banks.get(i % 3));
                transfer.put("destinationBankAccountId", banks.get((i + 1) % 3));
                clock.advance(Duration.ofSeconds(1));
                fixtures.transaction(author, transfer);
            } else {
                expense(author, date, "row" + i, categories.get(i % 4), banks.get(i % 3));
            }
        }
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.setStatisticsEnabled(true);
        try {
            get(couple[0], "?size=10"); // warm-up
            // An empty result runs only the page query (count and assembler are skipped): the rest is auth overhead.
            assertThat(statementsFor(statistics, couple[0], "?categoryId=" + UUID.randomUUID(), 0))
                    .isEqualTo(1 + AUTH_STATEMENTS);
            long small = statementsFor(statistics, couple[0], "?size=3", 12);
            long large = statementsFor(statistics, couple[0], "?size=10", 12);

            // Measured: 2 auth/entitlement + page + count + categories + banks + users = 7.
            assertThat(small).isEqualTo(EXPECTED_STATEMENTS);
            assertThat(large).isEqualTo(small);
        } finally {
            statistics.setStatisticsEnabled(false);
        }
    }

    private long statementsFor(org.hibernate.stat.Statistics statistics, TestSession s, String query, long total)
            throws Exception {
        statistics.clear();
        JsonNode page = body(get(s, query));
        assertThat(page.path("totalElements").asLong()).isEqualTo(total);
        assertThat(page.path("content").size()).isLessThan(12);
        return statistics.getPrepareStatementCount();
    }

    private static final long AUTH_STATEMENTS = 2;
    private static final long EXPECTED_STATEMENTS = 7;
}
