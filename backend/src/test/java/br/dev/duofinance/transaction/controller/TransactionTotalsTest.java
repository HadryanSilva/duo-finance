package br.dev.duofinance.transaction.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
class TransactionTotalsTest {

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

    private final JsonMapper json = JsonMapper.builder().build();

    private MvcTestResult get(TestSession s, String path) {
        return mvc.get()
                .uri(BASE + path)
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .exchange();
    }

    private JsonNode body(MvcTestResult result) throws Exception {
        assertThat(result).hasStatus(HttpStatus.OK);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void assertTotals(JsonNode totals, long income, long expense, long net, long count) {
        assertThat(totals.path("incomeCents").asLong()).as("incomeCents").isEqualTo(income);
        assertThat(totals.path("expenseCents").asLong()).as("expenseCents").isEqualTo(expense);
        assertThat(totals.path("netCents").asLong()).as("netCents").isEqualTo(net);
        assertThat(totals.path("count").asLong()).as("count").isEqualTo(count);
    }

    private UUID seeded(TestSession s, String kind) {
        return jdbc.queryForObject(
                "SELECT id FROM categories WHERE account_id = ? AND kind = ? LIMIT 1", UUID.class, s.accountId(), kind);
    }

    private UUID categorized(TestSession s, String kind, long cents, String date, String description, UUID bank) {
        Map<String, Object> request = new HashMap<>();
        request.put("kind", kind);
        request.put("amountCents", cents);
        request.put("date", date);
        request.put("description", description);
        request.put("categoryId", seeded(s, kind));
        if (bank != null) {
            request.put("bankAccountId", bank);
        }
        clock.advance(Duration.ofSeconds(1));
        return fixtures.transaction(s, request);
    }

    private UUID transfer(TestSession s, long cents, String date, UUID from, UUID to) {
        Map<String, Object> request = new HashMap<>();
        request.put("kind", "TRANSFER");
        request.put("amountCents", cents);
        request.put("date", date);
        request.put("bankAccountId", from);
        request.put("destinationBankAccountId", to);
        clock.advance(Duration.ofSeconds(1));
        return fixtures.transaction(s, request);
    }

    @Test
    void totals_sumIncomeAndExpense_ignoringTransfers() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID a = fixtures.bankAccount(s, "A", 0);
        UUID b = fixtures.bankAccount(s, "B", 0);
        categorized(s, "INCOME", 5000, "2026-11-02", "salary", a);
        categorized(s, "EXPENSE", 1200, "2026-11-03", "market", a);
        categorized(s, "EXPENSE", 300, "2026-11-04", "bakery", null);
        transfer(s, 999, "2026-11-05", a, b);

        assertTotals(body(get(s, "/totals")), 5000, 1500, 3500, 4);
    }

    @Test
    void totals_accountFilter_countsTransferInAsIncomeAndOutAsExpense() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID a = fixtures.bankAccount(s, "A", 0);
        UUID b = fixtures.bankAccount(s, "B", 0);
        categorized(s, "EXPENSE", 100, "2026-11-03", "market", a);
        transfer(s, 999, "2026-11-05", a, b);

        assertTotals(body(get(s, "/totals?bankAccountId=" + a)), 0, 1099, -1099, 2);
        assertTotals(body(get(s, "/totals?bankAccountId=" + b)), 999, 0, 999, 1);
    }

    @Test
    void totals_matchListForSameFilter() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID a = fixtures.bankAccount(s, "A", 0);
        UUID b = fixtures.bankAccount(s, "B", 0);
        for (int i = 0; i < 60; i++) {
            String date = "2026-%s-%02d".formatted(i % 3 == 0 ? "10" : "11", 1 + i % 28);
            String description = i % 2 == 0 ? "feira " + i : "padaria " + i;
            UUID bank = i % 4 == 0 ? b : a;
            if (i % 5 == 0) {
                transfer(s, 100 + i, date, i % 2 == 0 ? a : b, i % 2 == 0 ? b : a);
            } else {
                categorized(s, i % 7 == 0 ? "INCOME" : "EXPENSE", 100 + i, date, description, bank);
            }
        }
        String filter = "from=2026-11-01&to=2026-11-30&q=fei&bankAccountId=" + a;

        long listed = body(get(s, "?size=100&" + filter)).path("totalElements").asLong();

        assertThat(listed).isPositive();
        assertThat(body(get(s, "/totals?" + filter)).path("count").asLong()).isEqualTo(listed);
    }

    @Test
    void totals_kindTransferWithoutAccount_onlyCounts() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID a = fixtures.bankAccount(s, "A", 0);
        UUID b = fixtures.bankAccount(s, "B", 0);
        categorized(s, "EXPENSE", 100, "2026-11-03", "market", a);
        transfer(s, 999, "2026-11-05", a, b);
        transfer(s, 500, "2026-11-06", b, a);

        assertTotals(body(get(s, "/totals?kind=TRANSFER")), 0, 0, 0, 2);
    }

    @Test
    void totals_otherAccountsData_isNotSummed() throws Exception {
        TestSession ana = testUsers.verified("ana@mail.com");
        TestSession bob = testUsers.verified("bob@mail.com");
        UUID anaBank = fixtures.bankAccount(ana, "Main", 0);
        categorized(ana, "EXPENSE", 700, "2026-11-01", "ana-only", anaBank);
        categorized(bob, "EXPENSE", 40, "2026-11-01", "bob-only", null);

        assertTotals(body(get(bob, "/totals")), 0, 40, -40, 1);
        assertThat(get(bob, "/totals?bankAccountId=" + anaBank)).hasStatus(HttpStatus.NOT_FOUND);
        assertThat(get(bob, "/totals?categoryId=" + seeded(ana, "EXPENSE"))).hasStatus(HttpStatus.NOT_FOUND);
    }

    @Test
    void totals_fromAfterTo_isOutOfRange() {
        TestSession s = testUsers.verified("ana@mail.com");

        var result = get(s, "/totals?from=2026-11-05&to=2026-11-04");

        assertThat(result).hasStatus(HttpStatus.BAD_REQUEST);
        assertThat(result).bodyJson().extractingPath("$.errors[0].field").isEqualTo("from");
        assertThat(result).bodyJson().extractingPath("$.errors[0].code").isEqualTo("OutOfRange");
    }

    @Test
    void totals_emptyPeriod_isZeros() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");

        assertTotals(body(get(s, "/totals?from=2026-01-01&to=2026-01-31")), 0, 0, 0, 0);
    }

    @Test
    void totals_pathIsNotTakenAsAnId() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");

        JsonNode totals = body(get(s, "/totals"));

        assertThat(totals.has("count")).isTrue();
        assertThat(totals.has("id")).isFalse();
    }
}
