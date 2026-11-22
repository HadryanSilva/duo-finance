package br.dev.duofinance.review.repository;

import java.sql.Date;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * The month review's three statements. Like the dashboard, only {@code INCOME} and {@code EXPENSE} count: transfers
 * move money between the household's own bank accounts.
 */
@Repository
public class MonthReviewQuery {

    /** {@code firstMonth} is the month of the household's earliest transaction of any kind; null with none. */
    public record Meta(String currency, @Nullable YearMonth firstMonth) {}

    public record MonthTotals(long incomeCents, long expenseCents) {}

    public record CategorySpend(
            YearMonth month, UUID categoryId, String name, String color, String icon, boolean archived, long cents) {}

    private static final String META_SQL = """
            SELECT a.currency, (SELECT MIN(t.date) FROM transactions t WHERE t.account_id = a.id) AS first_date
            FROM accounts a
            WHERE a.id = :accountId
            """;

    private static final String TOTALS_SQL = """
            SELECT date_trunc('month', date)::date AS month, kind, SUM(amount_cents)::bigint AS total_cents
            FROM transactions
            WHERE account_id = :accountId
              AND kind IN ('INCOME', 'EXPENSE')
              AND date >= :first
              AND date <= :last
            GROUP BY 1, 2
            """;

    private static final String SPENDING_SQL = """
            SELECT date_trunc('month', t.date)::date AS month, c.id, c.name, c.color, c.icon,
                   c.archived_at IS NOT NULL AS archived, SUM(t.amount_cents)::bigint AS spent_cents
            FROM transactions t
            JOIN categories c ON c.id = t.category_id
            WHERE t.account_id = :accountId
              AND t.kind = 'EXPENSE'
              AND t.date >= :first
              AND t.date <= :last
            GROUP BY 1, c.id, c.name, c.color, c.icon, c.archived_at
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public MonthReviewQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Meta meta(UUID accountId) {
        return jdbc.queryForObject(META_SQL, new MapSqlParameterSource("accountId", accountId), (rs, n) -> {
            Date first = rs.getDate("first_date");
            return new Meta(rs.getString("currency"), first == null ? null : YearMonth.from(first.toLocalDate()));
        });
    }

    /** Months without income or expense are absent from the map. */
    public Map<YearMonth, MonthTotals> monthlyTotals(UUID accountId, YearMonth from, YearMonth to) {
        Map<YearMonth, long[]> sums = new HashMap<>();
        jdbc.query(TOTALS_SQL, range(accountId, from, to), rs -> {
            long[] pair = sums.computeIfAbsent(month(rs.getDate("month")), m -> new long[2]);
            pair["INCOME".equals(rs.getString("kind")) ? 0 : 1] = rs.getLong("total_cents");
        });
        Map<YearMonth, MonthTotals> result = new HashMap<>();
        sums.forEach((m, pair) -> result.put(m, new MonthTotals(pair[0], pair[1])));
        return result;
    }

    public List<CategorySpend> monthlySpending(UUID accountId, YearMonth from, YearMonth to) {
        List<CategorySpend> result = new ArrayList<>();
        jdbc.query(SPENDING_SQL, range(accountId, from, to), rs -> {
            result.add(new CategorySpend(
                    month(rs.getDate("month")),
                    rs.getObject("id", UUID.class),
                    rs.getString("name"),
                    rs.getString("color"),
                    rs.getString("icon"),
                    rs.getBoolean("archived"),
                    rs.getLong("spent_cents")));
        });
        return result;
    }

    private static MapSqlParameterSource range(UUID accountId, YearMonth from, YearMonth to) {
        return new MapSqlParameterSource()
                .addValue("accountId", accountId)
                .addValue("first", from.atDay(1))
                .addValue("last", to.atEndOfMonth());
    }

    private static YearMonth month(Date date) {
        LocalDate d = date.toLocalDate();
        return YearMonth.from(d);
    }
}
