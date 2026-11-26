package br.dev.duofinance.db;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import br.dev.duofinance.IntegrationTest;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** V10 folds DEPOSIT into CONTRIBUTION, dates every movement and allows many contributions per month. */
@IntegrationTest
class GoalContributionsBackfillTest {

    private static final String SCHEMA = "goal_backfill_check";

    @Autowired
    DataSource dataSource;

    @Autowired
    JdbcTemplate jdbc;

    private Flyway flyway(String target) {
        var config = Flyway.configure()
                .dataSource(dataSource)
                .schemas(SCHEMA)
                .defaultSchema(SCHEMA)
                .locations("classpath:db/migration");
        return (target == null ? config : config.target(target)).load();
    }

    @AfterEach
    void dropSchema() {
        jdbc.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }

    private void movement(UUID goalId, UUID accountId, String type, long cents, String month, String createdAt) {
        jdbc.update(
                "INSERT INTO " + SCHEMA + ".goal_movements (id, goal_id, account_id, type, amount_cents, month,"
                        + " created_at) VALUES (?, ?, ?, ?, ?, ?, ?)",
                UUID.randomUUID(),
                goalId,
                accountId,
                type,
                cents,
                month == null ? null : Date.valueOf(month),
                Timestamp.from(Instant.parse(createdAt)));
    }

    @Test
    void v10_foldsDepositsAndKeepsSavedTotals() {
        flyway("9").migrate();
        UUID accountId = UUID.randomUUID();
        UUID goalId = UUID.randomUUID();
        Timestamp now = Timestamp.from(Instant.parse("2026-10-01T00:00:00Z"));
        jdbc.update(
                "INSERT INTO " + SCHEMA + ".accounts (id, name, currency, created_at, updated_at)"
                        + " VALUES (?, 'Casa', 'EUR', ?, ?)",
                accountId,
                now,
                now);
        jdbc.update(
                "INSERT INTO " + SCHEMA + ".goals (id, account_id, name, target_cents, monthly_percent, created_at,"
                        + " updated_at) VALUES (?, ?, 'Viagem', 100000, 10, ?, ?)",
                goalId,
                accountId,
                now,
                now);
        movement(goalId, accountId, "CONTRIBUTION", 30000, "2026-10-01", "2026-10-15T09:00:00Z");
        movement(goalId, accountId, "DEPOSIT", 5000, null, "2026-11-07T10:00:00Z");
        movement(goalId, accountId, "WITHDRAWAL", 2000, null, "2026-11-08T10:00:00Z");

        flyway(null).migrate();

        String m = SCHEMA + ".goal_movements";
        assertThat(jdbc.queryForObject(
                        "SELECT type FROM " + m + " WHERE amount_cents = 5000", String.class))
                .isEqualTo("CONTRIBUTION");
        assertThat(jdbc.queryForObject(
                        "SELECT month FROM " + m + " WHERE amount_cents = 5000", LocalDate.class))
                .isEqualTo(LocalDate.parse("2026-11-01"));
        assertThat(jdbc.queryForObject(
                        "SELECT occurred_on FROM " + m + " WHERE amount_cents = 5000", LocalDate.class))
                .isEqualTo(LocalDate.parse("2026-11-07"));
        assertThat(jdbc.queryForObject(
                        "SELECT occurred_on FROM " + m + " WHERE amount_cents = 30000", LocalDate.class))
                .isEqualTo(LocalDate.parse("2026-10-01"));
        assertThat(jdbc.queryForObject(
                        "SELECT SUM(CASE type WHEN 'WITHDRAWAL' THEN -amount_cents ELSE amount_cents END) FROM " + m,
                        Long.class))
                .isEqualTo(33000L);

        // A second contribution in the same month is fine now; a second SKIP is not.
        String insert = "INSERT INTO " + m + " (id, goal_id, account_id, type, amount_cents, month, occurred_on,"
                + " created_at) VALUES (?, ?, ?, ?, ?, DATE '2026-11-01', DATE '2026-11-20', ?)";
        jdbc.update(insert, UUID.randomUUID(), goalId, accountId, "CONTRIBUTION", 1000, now);
        jdbc.update(insert, UUID.randomUUID(), goalId, accountId, "SKIP", 0, now);
        assertThatThrownBy(() -> jdbc.update(insert, UUID.randomUUID(), goalId, accountId, "SKIP", 0, now))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
