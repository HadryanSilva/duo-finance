package br.dev.duofinance.db;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.IntegrationTest;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Users who existed before V7 already went through onboarding (in their browser), so V7 marks them onboarded. */
@IntegrationTest
class OnboardingBackfillTest {

    private static final String SCHEMA = "backfill_check";

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

    @Test
    void v7_marksExistingUsersOnboarded() {
        flyway("6").migrate();
        UUID id = UUID.randomUUID();
        Instant createdAt = Instant.parse("2026-11-01T00:00:00Z");
        jdbc.update(
                "INSERT INTO " + SCHEMA + ".users (id, email, display_name, locale, terms_version, terms_accepted_at,"
                        + " created_at, updated_at) VALUES (?, 'old@mail.com', 'Old', 'en', '2026-11', ?, ?, ?)",
                id,
                java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt),
                java.sql.Timestamp.from(createdAt));

        flyway(null).migrate();

        assertThat(jdbc.queryForObject("SELECT onboarded_at FROM " + SCHEMA + ".users WHERE id = ?", Instant.class, id))
                .isEqualTo(createdAt);
    }
}
