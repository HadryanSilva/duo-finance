package br.dev.duofinance.goal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.category.model.CategoryKind;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
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
class GoalMovementControllerTest {

    private static final String GOALS = "/api/v1/goals";

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    @Autowired
    MutableClock clock;

    @Autowired
    JdbcTemplate jdbc;

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

    private void assertError(MvcTestResult result, HttpStatus status, String code) throws Exception {
        assertThat(result).hasStatus(status);
        assertThat(body(result).path("code").asString()).isEqualTo(code);
    }

    /** The "Salário" income category every new account is seeded with. */
    private UUID seededSalaryCategory(TestSession s) throws Exception {
        JsonNode categories = body(send(HttpMethod.GET, s, "/api/v1/categories?kind=INCOME", null));
        for (JsonNode c : categories) {
            if ("Salário".equals(c.path("name").asString())) {
                return UUID.fromString(c.path("id").asString());
            }
        }
        throw new IllegalStateException("Seeded salary category not found");
    }

    private void setSalaryCategory(TestSession s, UUID categoryId) {
        Map<String, Object> request = new HashMap<>();
        request.put("salaryCategoryId", categoryId);
        assertThat(send(HttpMethod.PUT, s, GOALS + "/settings", request)).hasStatus(HttpStatus.OK);
    }

    private void income(TestSession s, UUID bank, UUID category, String date, long cents) {
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("kind", "INCOME");
        tx.put("amountCents", cents);
        tx.put("date", date);
        tx.put("categoryId", category);
        tx.put("bankAccountId", bank);
        fixtures.transaction(s, tx);
    }

    /** Salary category set, with 200000 + 150000 received in 2027-03. */
    private void withSalary(TestSession s) throws Exception {
        UUID category = seededSalaryCategory(s);
        setSalaryCategory(s, category);
        UUID bank = fixtures.bankAccount(s, "Conta", 0);
        income(s, bank, category, "2027-03-05", 200000);
        income(s, bank, category, "2027-03-10", 150000);
    }

    private String createGoal(TestSession s, long target, int percent) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", "Meta");
        m.put("targetCents", target);
        m.put("monthlyPercent", percent);
        MvcTestResult r = send(HttpMethod.POST, s, GOALS, m);
        assertThat(r).hasStatus(HttpStatus.CREATED);
        return body(r).path("id").asString();
    }

    private MvcTestResult contribute(TestSession s, String goal, String date, Long amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amountCents", amount);
        if (date != null) {
            m.put("date", date);
        }
        return send(HttpMethod.POST, s, GOALS + "/" + goal + "/contributions", m);
    }

    private MvcTestResult skip(TestSession s, String goal, String month) {
        return send(HttpMethod.POST, s, GOALS + "/" + goal + "/skips", Map.of("month", month));
    }

    private MvcTestResult amount(TestSession s, String goal, String path, long cents, String note) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amountCents", cents);
        if (note != null) {
            m.put("note", note);
        }
        return send(HttpMethod.POST, s, GOALS + "/" + goal + "/" + path, m);
    }

    private JsonNode movements(TestSession s, String goal) throws Exception {
        MvcTestResult r = send(HttpMethod.GET, s, GOALS + "/" + goal + "/movements", null);
        assertThat(r).hasStatus(HttpStatus.OK);
        return body(r);
    }

    @Test
    void contribute_twiceSameMonth_bothStored() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);

        assertThat(contribute(s, goal, null, 10000L)).hasStatus(HttpStatus.CREATED);
        MvcTestResult second = contribute(s, goal, null, 5000L);

        assertThat(second).hasStatus(HttpStatus.CREATED);
        assertThat(body(second).path("savedCents").asLong()).isEqualTo(15000);
        assertThat(movements(s, goal)).hasSize(2);
    }

    @Test
    void contribute_withDate_usesItsMonth() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);

        assertThat(contribute(s, goal, "2027-03-03", 5000L)).hasStatus(HttpStatus.CREATED);

        JsonNode list = movements(s, goal);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("type").asString()).isEqualTo("CONTRIBUTION");
        assertThat(list.get(0).path("month").asString()).isEqualTo("2027-03");
        assertThat(list.get(0).path("occurredOn").asString()).isEqualTo("2027-03-03");
        assertThat(list.get(0).path("imported").asBoolean()).isFalse();
        assertThat(list.get(0).path("createdBy").path("userId").asString())
                .isEqualTo(s.userId().toString());
    }

    @Test
    void contribute_withoutDate_isDatedToday() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);

        assertThat(contribute(s, goal, null, 5000L)).hasStatus(HttpStatus.CREATED);

        JsonNode first = movements(s, goal).get(0);
        assertThat(first.path("occurredOn").asString()).isEqualTo("2027-03-15");
        assertThat(first.path("month").asString()).isEqualTo("2027-03");
    }

    @Test
    void contribute_noSalary_allowed() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);

        MvcTestResult r = contribute(s, goal, "2027-03-10", 50000L);

        assertThat(r).hasStatus(HttpStatus.CREATED);
        assertThat(body(r).path("savedCents").asLong()).isEqualTo(50000);
    }

    @Test
    void contribute_aboveRemaining_rejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 10000, 10);

        assertError(contribute(s, goal, "2027-03-10", 10001L), HttpStatus.BAD_REQUEST, "GOAL_AMOUNT_INVALID");
    }

    @Test
    void contribute_intoSkippedMonth_removesSkip() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);
        assertThat(skip(s, goal, "2027-03")).hasStatus(HttpStatus.CREATED);

        assertThat(contribute(s, goal, "2027-03-10", 5000L)).hasStatus(HttpStatus.CREATED);

        JsonNode list = movements(s, goal);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("type").asString()).isEqualTo("CONTRIBUTION");
    }

    @Test
    void skip_monthWithContribution_conflict() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);
        assertThat(contribute(s, goal, "2027-03-10", 5000L)).hasStatus(HttpStatus.CREATED);

        assertError(skip(s, goal, "2027-03"), HttpStatus.CONFLICT, "GOAL_MONTH_HAS_CONTRIBUTIONS");
    }

    @Test
    void skip_twice_conflict() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);
        assertThat(skip(s, goal, "2027-03")).hasStatus(HttpStatus.CREATED);

        assertError(skip(s, goal, "2027-03"), HttpStatus.CONFLICT, "GOAL_MONTH_TAKEN");
    }

    @Test
    void contribute_futureDate_rejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);

        assertError(contribute(s, goal, "2027-04-01", 1000L), HttpStatus.BAD_REQUEST, "GOAL_MONTH_INVALID");
        assertError(contribute(s, goal, "2027-03-17", 1000L), HttpStatus.BAD_REQUEST, "GOAL_MONTH_INVALID");
        assertThat(contribute(s, goal, "2027-03-16", 1000L)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void contribute_beforeFirstMonth_rejectedUnlessAMovementIsEarlier() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);

        assertError(contribute(s, goal, "2027-02-10", 1000L), HttpStatus.BAD_REQUEST, "GOAL_MONTH_INVALID");

        // An imported movement older than the goal opens the months from its own month on.
        jdbc.update(
                "INSERT INTO goal_movements (id, goal_id, account_id, type, amount_cents, month, occurred_on, created_at)"
                        + " SELECT gen_random_uuid(), id, account_id, 'CONTRIBUTION', 100, DATE '2027-01-01',"
                        + " DATE '2027-01-20', now() FROM goals WHERE id = ?",
                UUID.fromString(goal));
        assertThat(contribute(s, goal, "2027-01-25", 1000L)).hasStatus(HttpStatus.CREATED);
        assertError(contribute(s, goal, "2026-12-31", 1000L), HttpStatus.BAD_REQUEST, "GOAL_MONTH_INVALID");
    }

    @Test
    void contribute_missingAmount_rejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);

        assertThat(send(HttpMethod.POST, s, GOALS + "/" + goal + "/contributions", Map.of("date", "2027-03-10")))
                .hasStatus(HttpStatus.BAD_REQUEST);
    }

    @Test
    void deposits_endpointGone() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);

        MvcTestResult r = amount(s, goal, "deposits", 1000, null);

        assertThat(r.getResponse().getStatus()).isIn(404, 405);
    }

    @Test
    void undo_importedMovement_conflict() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);
        UUID bank = fixtures.bankAccount(s, "Conta", 0);
        Map<String, Object> tx = new LinkedHashMap<>();
        tx.put("kind", "EXPENSE");
        tx.put("amountCents", 3000);
        tx.put("date", "2027-03-10");
        tx.put("categoryId", fixtures.category(s, "Poupar", CategoryKind.EXPENSE));
        tx.put("bankAccountId", bank);
        UUID transactionId = fixtures.transaction(s, tx);
        jdbc.update(
                "INSERT INTO goal_movements (id, goal_id, account_id, type, amount_cents, month, occurred_on,"
                        + " transaction_id, created_at) SELECT gen_random_uuid(), id, account_id, 'CONTRIBUTION',"
                        + " 3000, DATE '2027-03-01', DATE '2027-03-10', ?, now() FROM goals WHERE id = ?",
                transactionId,
                UUID.fromString(goal));
        JsonNode list = movements(s, goal);
        assertThat(list.get(0).path("imported").asBoolean()).isTrue();

        assertError(
                send(
                        HttpMethod.DELETE,
                        s,
                        GOALS + "/" + goal + "/movements/"
                                + list.get(0).path("id").asString(),
                        null),
                HttpStatus.CONFLICT,
                "GOAL_MOVEMENT_IMPORTED");
        assertThat(movements(s, goal)).hasSize(1);
    }

    @Test
    void skip_recordsZero() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);

        MvcTestResult r = skip(s, goal, "2027-03");

        assertThat(r).hasStatus(HttpStatus.CREATED);
        assertThat(body(r).path("savedCents").asLong()).isZero();
        JsonNode list = movements(s, goal);
        assertThat(list).hasSize(1);
        assertThat(list.get(0).path("type").asString()).isEqualTo("SKIP");
        assertThat(list.get(0).path("amountCents").asLong()).isZero();
    }

    @Test
    void depositAndWithdraw_adjustSaved() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);

        MvcTestResult deposit = contribute(s, goal, null, 20000L);
        assertThat(deposit).hasStatus(HttpStatus.CREATED);
        assertThat(body(deposit).path("savedCents").asLong()).isEqualTo(20000);

        MvcTestResult withdrawal = amount(s, goal, "withdrawals", 5000, null);
        assertThat(withdrawal).hasStatus(HttpStatus.CREATED);
        assertThat(body(withdrawal).path("savedCents").asLong()).isEqualTo(15000);

        assertError(amount(s, goal, "withdrawals", 15001, null), HttpStatus.BAD_REQUEST, "GOAL_AMOUNT_INVALID");
        assertThat(movements(s, goal)).hasSize(2);
        MvcTestResult after = send(HttpMethod.GET, s, GOALS, null);
        assertThat(body(after).path("goals").get(0).path("savedCents").asLong()).isEqualTo(15000);
    }

    @Test
    void movements_sameInstant_newestFirst() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);
        assertThat(contribute(s, goal, null, 20000L)).hasStatus(HttpStatus.CREATED);
        assertThat(amount(s, goal, "withdrawals", 5000, null)).hasStatus(HttpStatus.CREATED);

        JsonNode list = movements(s, goal);

        assertThat(list.get(0).path("type").asString()).isEqualTo("WITHDRAWAL");
        assertThat(list.get(1).path("type").asString()).isEqualTo("CONTRIBUTION");
    }

    @Test
    void undo_contribution_bringsMonthBack() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);
        assertThat(contribute(s, goal, "2027-03-10", 5000L)).hasStatus(HttpStatus.CREATED);
        String movementId = movements(s, goal).get(0).path("id").asString();

        MvcTestResult undone = send(HttpMethod.DELETE, s, GOALS + "/" + goal + "/movements/" + movementId, null);

        assertThat(undone).hasStatus(HttpStatus.OK);
        assertThat(body(undone).path("savedCents").asLong()).isZero();
        assertThat(contribute(s, goal, "2027-03-10", 5000L)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void undo_skip_bringsMonthBack() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);
        assertThat(skip(s, goal, "2027-03")).hasStatus(HttpStatus.CREATED);
        assertError(skip(s, goal, "2027-03"), HttpStatus.CONFLICT, "GOAL_MONTH_TAKEN");
        String movementId = movements(s, goal).get(0).path("id").asString();

        MvcTestResult undone = send(HttpMethod.DELETE, s, GOALS + "/" + goal + "/movements/" + movementId, null);

        assertThat(undone).hasStatus(HttpStatus.OK);
        assertThat(body(undone).path("savedCents").asLong()).isZero();
        assertThat(movements(s, goal)).isEmpty();
        assertThat(skip(s, goal, "2027-03")).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void deposit_aboveRemaining_rejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 20000, 10);
        assertThat(contribute(s, goal, null, 15000L)).hasStatus(HttpStatus.CREATED);

        assertError(contribute(s, goal, null, 5001L), HttpStatus.BAD_REQUEST, "GOAL_AMOUNT_INVALID");

        assertThat(movements(s, goal)).hasSize(1);
        assertThat(contribute(s, goal, null, 5000L)).hasStatus(HttpStatus.CREATED);
    }

    @Test
    void undo_withdrawalAboveLoweredTarget_rejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 20000, 10);
        assertThat(contribute(s, goal, null, 15000L)).hasStatus(HttpStatus.CREATED);
        assertThat(amount(s, goal, "withdrawals", 5000, null)).hasStatus(HttpStatus.CREATED);
        Map<String, Object> lowered = new LinkedHashMap<>();
        lowered.put("name", "Meta");
        lowered.put("targetCents", 12000);
        lowered.put("monthlyPercent", 10);
        assertThat(send(HttpMethod.PUT, s, GOALS + "/" + goal, lowered)).hasStatus(HttpStatus.OK);
        String withdrawalId = movements(s, goal).get(0).path("id").asString();

        assertError(
                send(HttpMethod.DELETE, s, GOALS + "/" + goal + "/movements/" + withdrawalId, null),
                HttpStatus.BAD_REQUEST,
                "GOAL_AMOUNT_INVALID");
        assertThat(movements(s, goal)).hasSize(2);
    }

    @Test
    void undo_withdrawalUpToTarget_allowed() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 20000, 10);
        assertThat(contribute(s, goal, null, 20000L)).hasStatus(HttpStatus.CREATED);
        assertThat(amount(s, goal, "withdrawals", 5000, null)).hasStatus(HttpStatus.CREATED);
        String withdrawalId = movements(s, goal).get(0).path("id").asString();

        MvcTestResult undone = send(HttpMethod.DELETE, s, GOALS + "/" + goal + "/movements/" + withdrawalId, null);

        assertThat(undone).hasStatus(HttpStatus.OK);
        assertThat(body(undone).path("savedCents").asLong()).isEqualTo(20000);
        assertThat(body(undone).path("status").asString()).isEqualTo("REACHED");
    }

    /**
     * 23:30 UTC on 30 April is already 1 May in Lisbon (summer time, UTC+1): the app asks for May, and May is a
     * month that has begun, not a future one.
     */
    @Test
    void contribute_justAfterMidnightAheadOfUtc_acceptsTheNewMonth() throws Exception {
        clock.setInstant(Instant.parse("2027-04-30T23:30:00Z"));
        TestSession s = testUsers.verified("ana@mail.com");
        UUID salary = seededSalaryCategory(s);
        setSalaryCategory(s, salary);
        income(s, fixtures.bankAccount(s, "Conta", 0), salary, "2027-05-01", 200000);
        String goal = createGoal(s, 100000, 10);

        JsonNode overview = body(send(HttpMethod.GET, s, GOALS + "?month=2027-05", null));
        assertThat(overview.path("suggestions")).hasSize(1);
        assertThat(contribute(s, goal, "2027-05-01", 1000L)).hasStatus(HttpStatus.CREATED);
        assertError(contribute(s, goal, "2027-06-10", 100L), HttpStatus.BAD_REQUEST, "GOAL_MONTH_INVALID");
    }

    /**
     * 01:00 UTC on 1 May is still 30 April in São Paulo (UTC-3): a goal created then still belongs to April there,
     * so April can be contributed.
     */
    @Test
    void contribute_createdJustAfterUtcMidnight_acceptsTheMonthStillRunningBehindUtc() throws Exception {
        clock.setInstant(Instant.parse("2027-05-01T01:00:00Z"));
        TestSession s = testUsers.verified("ana@mail.com");
        UUID salary = seededSalaryCategory(s);
        setSalaryCategory(s, salary);
        income(s, fixtures.bankAccount(s, "Conta", 0), salary, "2027-04-30", 200000);
        String goal = createGoal(s, 100000, 10);

        JsonNode overview = body(send(HttpMethod.GET, s, GOALS + "?month=2027-04", null));
        assertThat(overview.path("suggestions")).hasSize(1);
        assertThat(contribute(s, goal, "2027-04-30", 1000L)).hasStatus(HttpStatus.CREATED);
        assertError(contribute(s, goal, "2027-03-10", 100L), HttpStatus.BAD_REQUEST, "GOAL_MONTH_INVALID");
    }

    @Test
    void undo_depositBelowZero_rejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);
        assertThat(contribute(s, goal, null, 10000L)).hasStatus(HttpStatus.CREATED);
        assertThat(amount(s, goal, "withdrawals", 8000, null)).hasStatus(HttpStatus.CREATED);
        String depositId = null;
        for (JsonNode m : movements(s, goal)) {
            if ("CONTRIBUTION".equals(m.path("type").asString())) {
                depositId = m.path("id").asString();
            }
        }

        assertError(
                send(HttpMethod.DELETE, s, GOALS + "/" + goal + "/movements/" + depositId, null),
                HttpStatus.BAD_REQUEST,
                "GOAL_AMOUNT_INVALID");
    }

    @Test
    void undo_movementOfAnotherGoal_notFound() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String first = createGoal(s, 100000, 10);
        Map<String, Object> other = new LinkedHashMap<>();
        other.put("name", "Outra");
        other.put("targetCents", 50000);
        other.put("monthlyPercent", 5);
        String second = body(send(HttpMethod.POST, s, GOALS, other)).path("id").asString();
        assertThat(contribute(s, first, null, 1000L)).hasStatus(HttpStatus.CREATED);
        String movementId = movements(s, first).get(0).path("id").asString();

        assertError(
                send(HttpMethod.DELETE, s, GOALS + "/" + second + "/movements/" + movementId, null),
                HttpStatus.NOT_FOUND,
                "NOT_FOUND");
    }

    @Test
    void movements_onCompletedGoal_rejected() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 100000, 10);
        assertThat(contribute(s, goal, null, 1000L)).hasStatus(HttpStatus.CREATED);
        String movementId = movements(s, goal).get(0).path("id").asString();
        assertThat(send(HttpMethod.POST, s, GOALS + "/" + goal + "/complete", null))
                .hasStatus(HttpStatus.OK);

        assertError(contribute(s, goal, null, 1000L), HttpStatus.CONFLICT, "GOAL_COMPLETED");
        assertError(amount(s, goal, "withdrawals", 500, null), HttpStatus.CONFLICT, "GOAL_COMPLETED");
        assertError(contribute(s, goal, "2027-03-10", 500L), HttpStatus.CONFLICT, "GOAL_COMPLETED");
        assertError(skip(s, goal, "2027-03"), HttpStatus.CONFLICT, "GOAL_COMPLETED");
        assertError(
                send(HttpMethod.DELETE, s, GOALS + "/" + goal + "/movements/" + movementId, null),
                HttpStatus.CONFLICT,
                "GOAL_COMPLETED");
    }

    @Test
    void reached_whenSavedHitsTarget() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String goal = createGoal(s, 20000, 10);

        MvcTestResult r = contribute(s, goal, null, 20000L);

        assertThat(r).hasStatus(HttpStatus.CREATED);
        assertThat(body(r).path("status").asString()).isEqualTo("REACHED");
        assertThat(body(r).path("remainingCents").asLong()).isZero();
    }

    @Test
    void concurrentSkips_oneWins() throws Exception {
        TestSession[] couple = testUsers.couple("ana@mail.com", "bia@mail.com");
        String goal = createGoal(couple[0], 100000, 10);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<Integer>> calls = new ArrayList<>();
            for (TestSession who : couple) {
                calls.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return skip(who, goal, "2027-03").getResponse().getStatus();
                }));
            }
            ready.await();
            go.countDown();
            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> f : calls) {
                statuses.add(f.get());
            }
            assertThat(statuses).containsExactlyInAnyOrder(201, 409);
        } finally {
            pool.shutdownNow();
        }

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM goal_movements WHERE goal_id = ? AND type = 'SKIP'",
                Integer.class,
                UUID.fromString(goal));
        assertThat(rows).isEqualTo(1);
    }
}
