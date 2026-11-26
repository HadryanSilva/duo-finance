package br.dev.duofinance.goal.repository;

import java.time.YearMonth;
import java.util.ArrayList;
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

    private static final String LAST_CONTRIBUTIONS_SQL = """
            SELECT goal_id, amount_cents
            FROM (SELECT goal_id, amount_cents, month, occurred_on,
                         ROW_NUMBER() OVER (PARTITION BY goal_id ORDER BY month DESC, occurred_on DESC) AS rn
                  FROM goal_movements
                  WHERE account_id = :accountId AND type = 'CONTRIBUTION') ranked
            WHERE rn <= 3
            ORDER BY goal_id, month DESC, occurred_on DESC
            """;

    private static final String DECIDED_SQL = """
            SELECT DISTINCT goal_id
            FROM goal_movements
            WHERE account_id = :accountId AND type IN ('CONTRIBUTION', 'SKIP') AND month = :month
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

    /** Each goal's last three contribution amounts, newest month first; deposits do not count. */
    public Map<UUID, List<Long>> lastContributionsByGoal(UUID accountId) {
        Map<UUID, List<Long>> result = new HashMap<>();
        jdbc.query(LAST_CONTRIBUTIONS_SQL, new MapSqlParameterSource("accountId", accountId), rs -> {
            result.computeIfAbsent(rs.getObject("goal_id", UUID.class), k -> new ArrayList<>())
                    .add(rs.getLong("amount_cents"));
        });
        return result;
    }

    /** Goals that already have a contribution or a skip for the month. */
    public Set<UUID> goalsDecidedIn(UUID accountId, YearMonth month) {
        Set<UUID> result = new HashSet<>();
        jdbc.query(
                DECIDED_SQL,
                new MapSqlParameterSource("accountId", accountId).addValue("month", month.atDay(1)),
                rs -> {
                    result.add(rs.getObject("goal_id", UUID.class));
                });
        return result;
    }
}
