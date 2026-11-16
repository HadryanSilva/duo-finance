package br.dev.duofinance.goal.repository;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** Reads and writes accounts.salary_category_id directly; the Account entity does not carry goal settings. */
@Repository
public class GoalSettingsRepository {

    private final NamedParameterJdbcTemplate jdbc;

    public GoalSettingsRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<UUID> salaryCategoryId(UUID accountId) {
        return jdbc
                .query(
                        "SELECT salary_category_id FROM accounts WHERE id = :accountId",
                        new MapSqlParameterSource("accountId", accountId),
                        (rs, i) -> rs.getObject("salary_category_id", UUID.class))
                .stream()
                .filter(Objects::nonNull)
                .findFirst();
    }

    public void setSalaryCategoryId(UUID accountId, @Nullable UUID categoryId) {
        jdbc.update(
                "UPDATE accounts SET salary_category_id = :categoryId WHERE id = :accountId",
                new MapSqlParameterSource().addValue("categoryId", categoryId).addValue("accountId", accountId));
    }
}
