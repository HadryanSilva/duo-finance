package br.dev.duofinance.recurring.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import br.dev.duofinance.support.MutableClock;
import br.dev.duofinance.support.TestUsers;
import br.dev.duofinance.support.TestUsers.TestSession;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.assertj.MockMvcTester;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.json.JsonMapper;

/**
 * Account deletion locks the account and then cascades into its rules; the job must take its locks in the same
 * order (account first), or the two deadlock.
 */
@IntegrationTest
class RecurringDeletionRaceTest {

    @Autowired
    MockMvcTester mvc;

    @Autowired
    TestUsers testUsers;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    DataSource dataSource;

    @Autowired
    MutableClock clock;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    RecurringGenerator generator;

    private final JsonMapper json = JsonMapper.builder().build();

    private UUID monthlyRule(TestSession s, String startDate) throws Exception {
        UUID category = jdbc.queryForObject(
                "SELECT id FROM categories WHERE account_id = ? AND kind = 'EXPENSE' ORDER BY name LIMIT 1",
                UUID.class,
                s.accountId());
        var result = mvc.post()
                .uri("/api/v1/recurring-rules")
                .header(HttpHeaders.AUTHORIZATION, TestUsers.bearer(s))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of(
                        "kind",
                        "EXPENSE",
                        "amountCents",
                        1000,
                        "categoryId",
                        category,
                        "frequency",
                        "MONTHLY",
                        "startDate",
                        startDate)))
                .exchange();
        assertThat(result).hasStatus(HttpStatus.CREATED);
        return UUID.fromString(json.readTree(result.getResponse().getContentAsString())
                .path("id")
                .asString());
    }

    private boolean anotherBackendWaitsOnLock(int ownPid) {
        Integer waiting = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND pid <> ?",
                Integer.class,
                ownPid);
        return waiting != null && waiting > 0;
    }

    @Test
    void generateDue_whileAccountIsBeingDeleted_waitsAndGeneratesNothing() throws Exception {
        clock.setInstant(Instant.parse("2027-03-15T10:00:00Z"));
        TestSession ana = testUsers.verified("ana@mail.com");
        UUID ruleId = monthlyRule(ana, "2027-03-15");
        clock.setInstant(Instant.parse("2027-04-15T10:00:00Z"));

        try (Connection deleter = dataSource.getConnection()) {
            deleter.setAutoCommit(false);
            int deleterPid;
            try (ResultSet rs = deleter.createStatement().executeQuery("SELECT pg_backend_pid()")) {
                rs.next();
                deleterPid = rs.getInt(1);
            }
            try (PreparedStatement lock = deleter.prepareStatement("SELECT id FROM accounts WHERE id = ? FOR UPDATE")) {
                lock.setObject(1, ana.accountId());
                lock.executeQuery().close();
            }

            AtomicReference<Integer> generated = new AtomicReference<>();
            AtomicReference<Throwable> failure = new AtomicReference<>();
            Thread job = new Thread(() -> {
                try {
                    generated.set(tx.execute(s -> generator.generateDue(ruleId, LocalDate.parse("2027-04-15"))));
                } catch (Throwable t) {
                    failure.set(t);
                }
            });
            job.start();

            long deadline = System.nanoTime() + 5_000_000_000L;
            while (!anotherBackendWaitsOnLock(deleterPid) && System.nanoTime() < deadline) {
                Thread.onSpinWait();
            }
            assertThat(anotherBackendWaitsOnLock(deleterPid))
                    .as("the job blocks on a lock held by the deletion")
                    .isTrue();

            // The job must not hold the rule yet: the deletion's cascade needs it.
            try (PreparedStatement rule =
                    deleter.prepareStatement("SELECT id FROM recurring_rules WHERE id = ? FOR UPDATE NOWAIT")) {
                rule.setObject(1, ruleId);
                rule.executeQuery().close();
            }
            try (PreparedStatement delete = deleter.prepareStatement("DELETE FROM accounts WHERE id = ?")) {
                delete.setObject(1, ana.accountId());
                delete.executeUpdate();
            }
            deleter.commit();

            job.join(10_000);
            assertThat(job.isAlive()).isFalse();
            assertThat(failure.get()).isNull();
            assertThat(generated.get()).isZero();
        }
        assertThat(jdbc.queryForObject(
                        "SELECT count(*) FROM transactions WHERE account_id = ?", Integer.class, ana.accountId()))
                .isZero();
    }
}
