package br.dev.duofinance.dashboard.repository;

import java.time.YearMonth;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Currency plus income and expense totals of one month in a single statement; transfers are excluded. */
@Repository
public class MonthlyTotalsQuery {

    public record Totals(String currency, long incomeCents, long expenseCents) {}

    private static final String SQL = """
            SELECT a.currency, t.kind, COALESCE(SUM(t.amount_cents), 0)::bigint AS total_cents
            FROM accounts a
            LEFT JOIN transactions t
                   ON t.account_id = a.id
                  AND t.kind IN ('INCOME', 'EXPENSE')
                  AND t.date >= :first
                  AND t.date <= :last
            WHERE a.id = :accountId
            GROUP BY a.currency, t.kind
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MonthlyTotalsQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Totals totals(UUID accountId, YearMonth month) {
        String[] currency = new String[1];
        long[] sums = new long[2];
        jdbc.query(
                SQL,
                new MapSqlParameterSource()
                        .addValue("accountId", accountId)
                        .addValue("first", month.atDay(1))
                        .addValue("last", month.atEndOfMonth()),
                rs -> {
                    currency[0] = rs.getString("currency");
                    String kind = rs.getString("kind");
                    if ("INCOME".equals(kind)) {
                        sums[0] = rs.getLong("total_cents");
                    } else if ("EXPENSE".equals(kind)) {
                        sums[1] = rs.getLong("total_cents");
                    }
                });
        return new Totals(currency[0], sums[0], sums[1]);
    }
}
