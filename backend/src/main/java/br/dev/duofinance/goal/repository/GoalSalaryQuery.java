package br.dev.duofinance.goal.repository;

import java.time.YearMonth;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Income actually received in a month under the account's salary category. */
@Repository
public class GoalSalaryQuery {

    private static final String SQL = """
            SELECT COALESCE(SUM(amount_cents), 0)::bigint
            FROM transactions
            WHERE account_id = :accountId
              AND category_id = :categoryId
              AND kind = 'INCOME'
              AND date >= :from AND date < :to
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public GoalSalaryQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public long salary(UUID accountId, UUID categoryId, YearMonth month) {
        Long sum = jdbc.queryForObject(
                SQL,
                new MapSqlParameterSource()
                        .addValue("accountId", accountId)
                        .addValue("categoryId", categoryId)
                        .addValue("from", month.atDay(1))
                        .addValue("to", month.plusMonths(1).atDay(1)),
                Long.class);
        return sum == null ? 0L : sum;
    }
}
