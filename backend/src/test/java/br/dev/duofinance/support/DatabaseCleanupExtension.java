package br.dev.duofinance.support;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.extension.BeforeEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit.jupiter.SpringExtension;

/** Truncates every table in the public schema (except Flyway history) before each test. */
public class DatabaseCleanupExtension implements BeforeEachCallback {

    @Override
    public void beforeEach(ExtensionContext context) {
        JdbcTemplate jdbc = SpringExtension.getApplicationContext(context).getBean(JdbcTemplate.class);
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables WHERE schemaname = 'public' AND tablename <> 'flyway_schema_history'",
                String.class);
        if (tables.isEmpty()) {
            return;
        }
        String names = tables.stream().map(t -> "\"" + t + "\"").collect(Collectors.joining(", "));
        jdbc.execute("TRUNCATE TABLE " + names + " RESTART IDENTITY CASCADE");
    }
}
