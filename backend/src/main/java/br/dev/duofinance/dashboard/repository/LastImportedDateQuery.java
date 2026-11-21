package br.dev.duofinance.dashboard.repository;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Latest date of an imported transaction per bank account, whatever month the dashboard shows. A transfer counts for
 * its origin account only.
 */
@Repository
public class LastImportedDateQuery {

    private static final String SQL = """
            SELECT t.bank_account_id AS id, MAX(t.date) AS last_date
            FROM transactions t
            WHERE t.account_id = :accountId
              AND t.import_batch_id IS NOT NULL
              AND t.bank_account_id IS NOT NULL
            GROUP BY t.bank_account_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public LastImportedDateQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Only bank accounts with at least one imported transaction have an entry. */
    public Map<UUID, LocalDate> lastImportedDates(UUID accountId) {
        Map<UUID, LocalDate> result = new HashMap<>();
        jdbc.query(SQL, new MapSqlParameterSource("accountId", accountId), rs -> {
            result.put(rs.getObject("id", UUID.class), rs.getObject("last_date", LocalDate.class));
        });
        return result;
    }
}
