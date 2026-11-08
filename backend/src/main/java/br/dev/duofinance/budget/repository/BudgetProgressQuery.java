package br.dev.duofinance.budget.repository;

import java.time.YearMonth;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Expense totals per category for one month in a single aggregate; there is no Transaction entity to join through. */
@Repository
public class BudgetProgressQuery {

    private static final String SQL = """
            SELECT category_id, SUM(amount_cents)::bigint AS spent_cents
            FROM transactions
            WHERE account_id = :accountId
              AND kind = 'EXPENSE'
              AND date >= :first
              AND date <= :last
            GROUP BY category_id
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public BudgetProgressQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Σ amount_cents of EXPENSE transactions dated within the month (future dates included), by category. */
    public Map<UUID, Long> spentByCategory(UUID accountId, YearMonth month) {
        Map<UUID, Long> result = new HashMap<>();
        jdbc.query(
                SQL,
                new MapSqlParameterSource()
                        .addValue("accountId", accountId)
                        .addValue("first", month.atDay(1))
                        .addValue("last", month.atEndOfMonth()),
                rs -> {
                    result.put(rs.getObject("category_id", UUID.class), rs.getLong("spent_cents"));
                });
        return result;
    }
}
