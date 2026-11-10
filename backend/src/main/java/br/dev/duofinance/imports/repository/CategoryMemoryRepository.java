package br.dev.duofinance.imports.repository;

import br.dev.duofinance.imports.service.PlanContext.MemoryKey;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class CategoryMemoryRepository {

    private final JdbcTemplate jdbc;

    public CategoryMemoryRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Remembered categories of the Account, restricted to categories that are still active. */
    public Map<MemoryKey, UUID> findActive(UUID accountId) {
        Map<MemoryKey, UUID> out = new HashMap<>();
        jdbc.query(
                """
                SELECT m.description_key, m.kind, m.category_id
                FROM category_memory m
                JOIN categories c ON c.id = m.category_id
                WHERE m.account_id = ? AND c.archived_at IS NULL
                """,
                rs -> {
                    out.put(
                            new MemoryKey(
                                    rs.getString("description_key"), TransactionKind.valueOf(rs.getString("kind"))),
                            rs.getObject("category_id", UUID.class));
                },
                accountId);
        return out;
    }
}
