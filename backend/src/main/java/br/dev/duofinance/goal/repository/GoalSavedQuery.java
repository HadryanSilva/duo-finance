package br.dev.duofinance.goal.repository;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Saved balance of every goal of an account in a single aggregate. */
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

    private final NamedParameterJdbcTemplate jdbc;

    public GoalSavedQuery(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Σ of contributions and deposits minus withdrawals, by goal; goals without movements are absent. */
    public Map<UUID, Long> savedByGoal(UUID accountId) {
        Map<UUID, Long> result = new HashMap<>();
        jdbc.query(SQL, new MapSqlParameterSource("accountId", accountId), rs -> {
            result.put(rs.getObject("goal_id", UUID.class), rs.getLong("saved_cents"));
        });
        return result;
    }
}
