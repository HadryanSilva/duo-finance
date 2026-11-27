package br.dev.duofinance.goal.repository;

import br.dev.duofinance.goal.model.Goal;
import br.dev.duofinance.shared.time.RunningMonths;
import java.sql.Date;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Aggregates over the goal movements of a whole account, one query each. */
@Repository
public class GoalSavedQuery {

    private static final String SQL = """
            SELECT goal_id,
                   SUM(CASE type WHEN 'WITHDRAWAL' THEN -amount_cents WHEN 'SKIP' THEN 0 ELSE amount_cents END)::bigint
                       AS saved_cents
            FROM goal_movements
            WHERE account_id = :accountId
            GROUP BY goal_id
            """;

    private static final String ONE_GOAL_SQL = """
            SELECT COALESCE(SUM(CASE type WHEN 'WITHDRAWAL' THEN -amount_cents WHEN 'SKIP' THEN 0 ELSE amount_cents END), 0)::bigint
            FROM goal_movements
            WHERE goal_id = :goalId
            """;

    private static final String LAST_MONTHLY_TOTALS_SQL = """
            SELECT goal_id, total
            FROM (SELECT goal_id, month, SUM(amount_cents)::bigint AS total,
                         ROW_NUMBER() OVER (PARTITION BY goal_id ORDER BY month DESC) AS rn
                  FROM goal_movements
                  WHERE account_id = :accountId AND type = 'CONTRIBUTION'
                  GROUP BY goal_id, month) ranked
            WHERE rn <= 3
            ORDER BY goal_id, month DESC
            """;

    private static final String CONTRIBUTED_SQL = """
            SELECT goal_id, SUM(amount_cents)::bigint AS total
            FROM goal_movements
            WHERE account_id = :accountId AND type = 'CONTRIBUTION' AND month = :month
            GROUP BY goal_id
            """;

    private static final String SKIPPED_SQL = """
            SELECT goal_id
            FROM goal_movements
            WHERE account_id = :accountId AND type = 'SKIP' AND month = :month
            """;

    private static final String EARLIEST_MOVEMENT_SQL = """
            SELECT goal_id, MIN(date_trunc('month', occurred_on))::date AS first_month
            FROM goal_movements
            WHERE account_id = :accountId
            GROUP BY goal_id
            """;

    private static final String ONE_EARLIEST_MOVEMENT_SQL = """
            SELECT MIN(date_trunc('month', occurred_on))::date
            FROM goal_movements
            WHERE goal_id = :goalId
            """;

    private final NamedParameterJdbcTemplate jdbc;

    public GoalSavedQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Σ of contributions minus withdrawals, by goal; goals without movements are absent. */
    public Map<UUID, Long> savedByGoal(UUID accountId) {
        Map<UUID, Long> result = new HashMap<>();
        jdbc.query(SQL, new MapSqlParameterSource("accountId", accountId), rs -> {
            result.put(rs.getObject("goal_id", UUID.class), rs.getLong("saved_cents"));
        });
        return result;
    }

    /** The saved amount of one goal, 0 without movements. */
    public long savedOf(UUID goalId) {
        Long result = jdbc.queryForObject(ONE_GOAL_SQL, new MapSqlParameterSource("goalId", goalId), Long.class);
        return result == null ? 0 : result;
    }

    /**
     * Each goal's Σ CONTRIBUTION of its last three months that had any, newest month first; goals without
     * contributions are absent.
     */
    public Map<UUID, List<Long>> lastMonthlyTotals(UUID accountId) {
        Map<UUID, List<Long>> result = new HashMap<>();
        jdbc.query(LAST_MONTHLY_TOTALS_SQL, new MapSqlParameterSource("accountId", accountId), rs -> {
            result.computeIfAbsent(rs.getObject("goal_id", UUID.class), k -> new ArrayList<>())
                    .add(rs.getLong("total"));
        });
        return result;
    }

    /** Σ CONTRIBUTION per goal in the month; goals without one are absent. */
    public Map<UUID, Long> contributedIn(UUID accountId, YearMonth month) {
        Map<UUID, Long> result = new HashMap<>();
        jdbc.query(CONTRIBUTED_SQL, monthParams(accountId, month), rs -> {
            result.put(rs.getObject("goal_id", UUID.class), rs.getLong("total"));
        });
        return result;
    }

    /** Goals skipped for the month. */
    public Set<UUID> skippedIn(UUID accountId, YearMonth month) {
        Set<UUID> result = new HashSet<>();
        jdbc.query(SKIPPED_SQL, monthParams(accountId, month), rs -> {
            result.add(rs.getObject("goal_id", UUID.class));
        });
        return result;
    }

    private static MapSqlParameterSource monthParams(UUID accountId, YearMonth month) {
        return new MapSqlParameterSource("accountId", accountId).addValue("month", month.atDay(1));
    }

    /**
     * The first month a goal can hold movements in: the earlier of the month it was created in (anywhere on Earth)
     * and the month of its earliest movement, which an import may place before the goal existed.
     */
    public YearMonth firstMonthOf(Goal goal) {
        Date earliest = jdbc.queryForObject(
                ONE_EARLIEST_MOVEMENT_SQL, new MapSqlParameterSource("goalId", goal.getId()), Date.class);
        return firstMonth(goal, earliest);
    }

    /** {@link #firstMonthOf} for the given goals of one account in a single query. */
    public Map<UUID, YearMonth> firstMonthsByGoal(UUID accountId, Collection<Goal> goals) {
        Map<UUID, Date> earliest = new HashMap<>();
        jdbc.query(EARLIEST_MOVEMENT_SQL, new MapSqlParameterSource("accountId", accountId), rs -> {
            earliest.put(rs.getObject("goal_id", UUID.class), rs.getDate("first_month"));
        });
        Map<UUID, YearMonth> result = new HashMap<>();
        for (Goal goal : goals) {
            result.put(goal.getId(), firstMonth(goal, earliest.get(goal.getId())));
        }
        return result;
    }

    private static YearMonth firstMonth(Goal goal, Date earliestMovement) {
        YearMonth created = RunningMonths.earliestOf(goal.getCreatedAt());
        if (earliestMovement == null) {
            return created;
        }
        YearMonth movement = YearMonth.from(earliestMovement.toLocalDate());
        return movement.isBefore(created) ? movement : created;
    }
}
