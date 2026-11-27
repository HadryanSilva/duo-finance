package br.dev.duofinance.goal.controller;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.goal.model.GoalMovement;
import br.dev.duofinance.goal.model.GoalMovementType;
import br.dev.duofinance.goal.repository.GoalMovementRepository;
import br.dev.duofinance.support.FinanceFixtures;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.test.web.servlet.assertj.MvcTestResult;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class GoalOverviewTest {

    private static final String GOALS = "/api/v1/goals";
    private static final Instant MARCH = Instant.parse("2027-03-15T10:00:00Z");

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    FinanceFixtures fixtures;

    @Autowired
    MutableClock clock;

    @Autowired
    GoalMovementRepository movements;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setClock() {
        clock.setInstant(MARCH);
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

    private JsonNode overview(TestSession s, String month) throws Exception {
        MvcTestResult r = send(HttpMethod.GET, s, GOALS + "?month=" + month, null);
        assertThat(r).hasStatus(HttpStatus.OK);
        return body(r);
    }

    /** The "Salário" income category every new account is seeded with. */
    private UUID seededSalaryCategory(TestSession s) throws Exception {
        for (JsonNode c : body(send(HttpMethod.GET, s, "/api/v1/categories?kind=INCOME", null))) {
            if ("Salário".equals(c.path("name").asString())) {
                return UUID.fromString(c.path("id").asString());
            }
        }
        throw new IllegalStateException("Seeded salary category not found");
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

    /** Sets the seeded "Salário" as the salary category and returns it. */
    private UUID salaryCategory(TestSession s) throws Exception {
        UUID category = seededSalaryCategory(s);
        Map<String, Object> request = new HashMap<>();
        request.put("salaryCategoryId", category);
        assertThat(send(HttpMethod.PUT, s, GOALS + "/settings", request)).hasStatus(HttpStatus.OK);
        return category;
    }

    /** Salary category set with 200000 + 150000 received in 2027-03. */
    private void withSalary(TestSession s) throws Exception {
        UUID category = salaryCategory(s);
        UUID bank = fixtures.bankAccount(s, "Conta", 0);
        income(s, bank, category, "2027-03-05", 200000);
        income(s, bank, category, "2027-03-10", 150000);
    }

    private String goal(TestSession s, String name, long target, int percent, String targetDate) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("targetCents", target);
        m.put("monthlyPercent", percent);
        if (targetDate != null) {
            m.put("targetDate", targetDate);
        }
        MvcTestResult r = send(HttpMethod.POST, s, GOALS, m);
        assertThat(r).hasStatus(HttpStatus.CREATED);
        return body(r).path("id").asString();
    }

    private String goal(TestSession s, String name, long target, int percent) throws Exception {
        return goal(s, name, target, percent, null);
    }

    private MvcTestResult contribute(TestSession s, String goal, String date, long amount) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("amountCents", amount);
        m.put("date", date);
        return send(HttpMethod.POST, s, GOALS + "/" + goal + "/contributions", m);
    }

    @Test
    void monthTargets_percentOfCombinedSalary_cappedAtRemaining() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        withSalary(s);
        String a = goal(s, "A", 100000, 10);
        String b = goal(s, "B", 10000, 5);

        JsonNode o = overview(s, "2027-03");

        assertThat(o.path("salaryCents").asLong()).isEqualTo(350000);
        assertThat(o.path("monthTargets")).hasSize(2);
        assertThat(o.path("monthTargets").get(0).path("goalId").asString()).isEqualTo(a);
        assertThat(o.path("monthTargets").get(0).path("targetCents").asLong()).isEqualTo(35000);
        assertThat(o.path("monthTargets").get(0).path("month").asString()).isEqualTo("2027-03");
        assertThat(o.path("monthTargets").get(1).path("goalId").asString()).isEqualTo(b);
        assertThat(o.path("monthTargets").get(1).path("targetCents").asLong()).isEqualTo(10000);
    }

    @Test
    void monthTargets_roundHalfUp() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = salaryCategory(s);
        income(s, fixtures.bankAccount(s, "Conta", 0), category, "2027-03-05", 333335);
        goal(s, "A", 1000000, 10);

        JsonNode o = overview(s, "2027-03");

        assertThat(o.path("monthTargets").get(0).path("targetCents").asLong()).isEqualTo(33334);
    }

    @Test
    void overview_monthTarget_tracksProgress() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = salaryCategory(s);
        income(s, fixtures.bankAccount(s, "Conta", 0), category, "2027-03-05", 300000);
        String a = goal(s, "A", 100000, 10);
        assertThat(contribute(s, a, "2027-03-10", 10000L)).hasStatus(HttpStatus.CREATED);
        assertThat(contribute(s, a, "2027-03-12", 5000L)).hasStatus(HttpStatus.CREATED);

        JsonNode targets = overview(s, "2027-03").path("monthTargets");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).path("goalId").asString()).isEqualTo(a);
        assertThat(targets.get(0).path("targetCents").asLong()).isEqualTo(30000);
        assertThat(targets.get(0).path("contributedCents").asLong()).isEqualTo(15000);
        assertThat(targets.get(0).path("skipped").asBoolean()).isFalse();
    }

    @Test
    void overview_monthTarget_metStaysListed() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = salaryCategory(s);
        income(s, fixtures.bankAccount(s, "Conta", 0), category, "2027-03-05", 300000);
        String a = goal(s, "A", 100000, 10);
        assertThat(contribute(s, a, "2027-03-10", 30000L)).hasStatus(HttpStatus.CREATED);

        JsonNode targets = overview(s, "2027-03").path("monthTargets");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).path("targetCents").asLong()).isEqualTo(30000);
        assertThat(targets.get(0).path("contributedCents").asLong()).isGreaterThanOrEqualTo(30000);
    }

    @Test
    void overview_skippedMonth_flagged() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        withSalary(s);
        String a = goal(s, "A", 100000, 10);
        assertThat(send(HttpMethod.POST, s, GOALS + "/" + a + "/skips", Map.of("month", "2027-03")))
                .hasStatus(HttpStatus.CREATED);

        JsonNode targets = overview(s, "2027-03").path("monthTargets");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).path("skipped").asBoolean()).isTrue();
        assertThat(targets.get(0).path("contributedCents").asLong()).isZero();
    }

    @Test
    void overview_noSalary_targetZero() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String a = goal(s, "A", 100000, 10);

        JsonNode targets = overview(s, "2027-03").path("monthTargets");

        assertThat(targets).hasSize(1);
        assertThat(targets.get(0).path("goalId").asString()).isEqualTo(a);
        assertThat(targets.get(0).path("targetCents").asLong()).isZero();
    }

    @Test
    void overview_monthTarget_listedFromTheMonthOfAnEarlierMovement() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = salaryCategory(s);
        income(s, fixtures.bankAccount(s, "Conta", 0), category, "2027-02-05", 200000);
        String a = goal(s, "A", 100000, 10);
        // An import may place a movement before the goal existed; that month then counts as its first.
        movements.save(new GoalMovement(
                UUID.fromString(a),
                s.accountId(),
                GoalMovementType.CONTRIBUTION,
                1000L,
                LocalDate.of(2027, 2, 1),
                LocalDate.of(2027, 2, 10),
                null,
                null,
                s.userId(),
                MARCH));

        assertThat(overview(s, "2027-02").path("monthTargets")).hasSize(1);
        assertThat(overview(s, "2027-01").path("monthTargets")).isEmpty();
    }

    @Test
    void monthTargets_pastMonthStillOffered() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = salaryCategory(s);
        clock.setInstant(Instant.parse("2027-02-01T10:00:00Z"));
        goal(s, "A", 100000, 10);
        income(s, fixtures.bankAccount(s, "Conta", 0), category, "2027-02-05", 200000);
        clock.setInstant(MARCH);

        JsonNode o = overview(s, "2027-02");

        assertThat(o.path("monthTargets")).hasSize(1);
        assertThat(o.path("monthTargets").get(0).path("targetCents").asLong()).isEqualTo(20000);
    }

    @Test
    void monthTargets_none_beforeCreationOrInFuture() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = salaryCategory(s);
        UUID bank = fixtures.bankAccount(s, "Conta", 0);
        income(s, bank, category, "2027-02-05", 200000);
        income(s, bank, category, "2027-03-05", 200000);
        income(s, bank, category, "2027-04-05", 200000);
        goal(s, "A", 100000, 10);

        assertThat(overview(s, "2027-02").path("monthTargets")).isEmpty();
        assertThat(overview(s, "2027-04").path("monthTargets")).isEmpty();
        assertThat(overview(s, "2027-03").path("monthTargets")).hasSize(1);
    }

    @Test
    void monthTargets_none_whenZeroPercentOrReached() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        withSalary(s);
        goal(s, "Zero", 100000, 0);
        String control = goal(s, "Control", 100000, 10);
        String reached = goal(s, "Reached", 5000, 10);
        assertThat(contribute(s, reached, "2027-03-10", 5000L)).hasStatus(HttpStatus.CREATED);

        JsonNode suggestions = overview(s, "2027-03").path("monthTargets");
        assertThat(suggestions).hasSize(1);
        assertThat(suggestions.get(0).path("goalId").asString()).isEqualTo(control);
    }

    @Test
    void monthTargets_none_whenSalaryCategoryArchived() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = salaryCategory(s);
        income(s, fixtures.bankAccount(s, "Conta", 0), category, "2027-03-05", 200000);
        goal(s, "A", 100000, 10);
        assertThat(overview(s, "2027-03").path("monthTargets")).hasSize(1);
        assertThat(send(HttpMethod.POST, s, "/api/v1/categories/" + category + "/archive", null))
                .hasStatus(HttpStatus.OK);

        JsonNode o = overview(s, "2027-03");

        assertThat(o.path("salaryCategory").path("archived").asBoolean()).isTrue();
        assertThat(o.path("monthTargets")).hasSize(1);
        assertThat(o.path("monthTargets").get(0).path("targetCents").asLong()).isZero();
        assertThat(o.path("salaryCents").asLong()).isZero();
    }

    @Test
    void salaryCategoryDeleted_overviewStillWorks() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = salaryCategory(s);
        goal(s, "A", 100000, 10);
        assertThat(overview(s, "2027-03").path("salaryCategory").isNull()).isFalse();
        assertThat(send(HttpMethod.DELETE, s, "/api/v1/categories/" + category, null))
                .hasStatus(HttpStatus.NO_CONTENT);

        JsonNode o = overview(s, "2027-03");

        assertThat(o.path("salaryCategory").isNull()).isTrue();
        assertThat(o.path("monthTargets").get(0).path("targetCents").asLong()).isZero();
    }

    @Test
    void overview_reportsProjectionAndTotal() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID category = salaryCategory(s);
        UUID bank = fixtures.bankAccount(s, "Conta", 0);
        income(s, bank, category, "2027-01-05", 100000);
        income(s, bank, category, "2027-02-05", 100000);
        income(s, bank, category, "2027-03-05", 100000);
        clock.setInstant(Instant.parse("2027-01-10T10:00:00Z"));
        String dated = goal(s, "Dated", 100000, 10, "2027-08-20");
        String open = goal(s, "Open", 120000, 10);
        String done = goal(s, "Done", 50000, 10);
        clock.setInstant(MARCH);
        assertThat(contribute(s, dated, "2027-03-10", 40000L)).hasStatus(HttpStatus.CREATED);
        assertThat(contribute(s, open, "2027-03-10", 30000L)).hasStatus(HttpStatus.CREATED);
        assertThat(contribute(s, open, "2027-02-10", 20000L)).hasStatus(HttpStatus.CREATED);
        assertThat(contribute(s, open, "2027-01-10", 10000L)).hasStatus(HttpStatus.CREATED);
        assertThat(contribute(s, done, "2027-03-10", 7000L)).hasStatus(HttpStatus.CREATED);
        assertThat(send(HttpMethod.POST, s, GOALS + "/" + done + "/complete", null))
                .hasStatus(HttpStatus.OK);

        JsonNode o = overview(s, "2027-03");

        JsonNode goals = o.path("goals");
        assertThat(goals).hasSize(2);
        // March is already contributed: the 60000 left spread over April to August.
        assertThat(goals.get(0).path("projection").path("requiredMonthlyCents").asLong())
                .isEqualTo(12000);
        assertThat(goals.get(1).path("projection").path("projectedMonth").asString())
                .isEqualTo("2027-06");
        assertThat(o.path("completed").get(0).path("projection").isNull()).isTrue();
        assertThat(o.path("totalSavedCents").asLong()).isEqualTo(40000 + 60000);
    }

    @Test
    void overview_sameCreationInstant_keepsCreationOrderAfterAnEdit() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        String first = goal(s, "First", 100000, 10);
        String second = goal(s, "Second", 100000, 10);
        // A rename rewrites the row and its index entries, so without a tiebreaker it may come back last.
        Map<String, Object> edit = new LinkedHashMap<>();
        edit.put("name", "First renamed");
        edit.put("targetCents", 200000);
        edit.put("monthlyPercent", 10);
        assertThat(send(HttpMethod.PUT, s, GOALS + "/" + first, edit)).hasStatus(HttpStatus.OK);

        JsonNode goals = overview(s, "2027-03").path("goals");

        assertThat(goals.get(0).path("id").asString()).isEqualTo(first);
        assertThat(goals.get(1).path("id").asString()).isEqualTo(second);
    }
}
