package br.dev.duofinance.recurring.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.recurring.service.RecurringGenerationJob.JobResult;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

@IntegrationTest
class RecurringGenerationJobTest {

    private static final Instant START = Instant.parse("2027-03-15T10:00:00Z");

    @Autowired
    RecurringGenerationJob job;

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MutableClock clock;

    @Autowired
    TransactionTemplate tx;

    @MockitoSpyBean
    RecurringGenerator generator;

    private final JsonMapper json = JsonMapper.builder().build();

    @BeforeEach
    void setClock() {
        clock.setInstant(START);
    }

    private UUID rule(TestSession s, String frequency, String startDate, String endDate) throws Exception {
        UUID category = jdbc.queryForObject(
                "SELECT id FROM categories WHERE account_id = ? AND kind = 'EXPENSE' ORDER BY name LIMIT 1",
                UUID.class,
                s.accountId());
        Map<String, Object> body = new HashMap<>();
        body.put("kind", "EXPENSE");
        body.put("amountCents", 1000);
        body.put("categoryId", category);
        body.put("frequency", frequency);
        body.put("startDate", startDate);
        body.put("endDate", endDate);
        var result = mvc.post()
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

    /** The generator is transactional (MANDATORY), so stubbing and verifying through its proxy needs a transaction. */
    private void inTx(Runnable stubbing) {
        tx.executeWithoutResult(status -> stubbing.run());
    }

    private int count(UUID ruleId) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM transactions WHERE recurring_rule_id = ?", Integer.class, ruleId);
    }

    private LocalDate nextRun(UUID ruleId) {
        return jdbc.queryForObject("SELECT next_run_date FROM recurring_rules WHERE id = ?", LocalDate.class, ruleId);
    }

    @Test
    void job_generatesDueOccurrencesAcrossAccounts() throws Exception {
        UUID a = rule(testUsers.verified("ana@mail.com"), "MONTHLY", "2027-03-15", null);
        UUID b = rule(testUsers.verified("bia@mail.com"), "MONTHLY", "2027-03-15", null);
        clock.setInstant(Instant.parse("2027-04-15T10:00:00Z"));

        JobResult result = job.run();

        assertThat(result).isEqualTo(new JobResult(2, 2, 0, 0));
        assertThat(count(a)).isEqualTo(2);
        assertThat(count(b)).isEqualTo(2);
        assertThat(nextRun(a)).isEqualTo(LocalDate.parse("2027-05-15"));
    }

    @Test
    void job_runTwice_isIdempotent() throws Exception {
        UUID a = rule(testUsers.verified("ana@mail.com"), "MONTHLY", "2027-03-15", null);
        clock.setInstant(Instant.parse("2027-04-15T10:00:00Z"));

        JobResult first = job.run();
        JobResult second = job.run();

        assertThat(first.generated()).isEqualTo(1);
        assertThat(second).isEqualTo(new JobResult(0, 0, 0, 0));
        assertThat(count(a)).isEqualTo(2);
    }

    @Test
    void job_skipsAccountsWithoutAccess() throws Exception {
        TestSession s = testUsers.verified("ana@mail.com");
        UUID a = rule(s, "MONTHLY", "2027-03-15", null);
        jdbc.update("UPDATE subscriptions SET status = 'EXPIRED' WHERE account_id = ?", s.accountId());
        clock.setInstant(Instant.parse("2027-04-15T10:00:00Z"));

        clearInvocations(generator); // the rule creation ran generateDue too
        JobResult result = job.run();

        assertThat(result).isEqualTo(new JobResult(1, 0, 1, 0));
        assertThat(count(a)).isEqualTo(1);
        assertThat(nextRun(a)).isEqualTo(LocalDate.parse("2027-04-15"));
        inTx(() -> verify(generator, never()).generateDue(eq(a), any()));
    }

    @Test
    void job_catchesUpMissedDays() throws Exception {
        UUID a = rule(testUsers.verified("ana@mail.com"), "WEEKLY", "2027-03-15", null);
        clock.setInstant(Instant.parse("2027-04-05T10:00:00Z"));

        JobResult result = job.run();

        assertThat(result.generated()).isEqualTo(3);
        assertThat(count(a)).isEqualTo(4);
        assertThat(nextRun(a)).isEqualTo(LocalDate.parse("2027-04-12"));
    }

    @Test
    void job_oneFailingRuleDoesNotStopOthers() throws Exception {
        UUID bad = rule(testUsers.verified("ana@mail.com"), "MONTHLY", "2027-03-15", null);
        UUID good = rule(testUsers.verified("bia@mail.com"), "MONTHLY", "2027-03-15", null);
        inTx(() -> doThrow(new IllegalStateException("boom")).when(generator).generateDue(eq(bad), any()));
        clock.setInstant(Instant.parse("2027-04-15T10:00:00Z"));

        JobResult result = job.run();

        assertThat(result).isEqualTo(new JobResult(2, 1, 0, 1));
        assertThat(count(bad)).isEqualTo(1);
        assertThat(nextRun(bad)).isEqualTo(LocalDate.parse("2027-04-15"));
        assertThat(count(good)).isEqualTo(2);
    }

    @Test
    void job_respectsEndDate() throws Exception {
        UUID a = rule(testUsers.verified("ana@mail.com"), "MONTHLY", "2027-03-15", "2027-05-15");
        clock.setInstant(Instant.parse("2027-09-01T10:00:00Z"));

        JobResult result = job.run();

        assertThat(result.generated()).isEqualTo(2);
        assertThat(count(a)).isEqualTo(3);
        // Finished: the next run is past the end date, so a rerun generates nothing.
        assertThat(job.run().generated()).isZero();
        assertThat(count(a)).isEqualTo(3);
    }

    @Test
    void job_ruleDeactivatedAfterListing_generatesNothing() throws Exception {
        UUID a = rule(testUsers.verified("ana@mail.com"), "MONTHLY", "2027-03-15", null);
        clock.setInstant(Instant.parse("2027-04-15T10:00:00Z"));
        inTx(() -> doAnswer(call -> {
                    jdbc.update("UPDATE recurring_rules SET active = false WHERE id = ?", a);
                    return call.callRealMethod();
                })
                .when(generator)
                .generateDue(eq(a), any()));

        JobResult result = job.run();

        assertThat(result).isEqualTo(new JobResult(1, 0, 0, 0));
        assertThat(count(a)).isEqualTo(1);
        assertThat(nextRun(a)).isEqualTo(LocalDate.parse("2027-04-15"));
    }
}
