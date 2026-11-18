package br.dev.duofinance.imports.repository;

import br.dev.duofinance.imports.service.PlanContext.MemoryKey;
import br.dev.duofinance.imports.service.PlanContext.RememberedCategory;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.sql.Timestamp;
import java.time.Instant;
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
    public Map<MemoryKey, RememberedCategory> findActive(UUID accountId) {
        Map<MemoryKey, RememberedCategory> out = new HashMap<>();
        jdbc.query(
                """
                SELECT m.description_key, m.kind, m.category_id, m.updated_at
                FROM category_memory m
                JOIN categories c ON c.id = m.category_id
                WHERE m.account_id = ? AND c.archived_at IS NULL
                """,
                rs -> {
                    out.put(
                            new MemoryKey(
                                    rs.getString("description_key"), TransactionKind.valueOf(rs.getString("kind"))),
                            new RememberedCategory(
                                    rs.getObject("category_id", UUID.class),
                                    rs.getTimestamp("updated_at").toInstant()));
                },
                accountId);
        return out;
    }

    /** Remembers the category for the description key and kind; the last call wins. */
    public void upsert(UUID accountId, String descriptionKey, TransactionKind kind, UUID categoryId, Instant now) {
        jdbc.update("""
                INSERT INTO category_memory (account_id, description_key, kind, category_id, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (account_id, description_key, kind)
                DO UPDATE SET category_id = EXCLUDED.category_id, updated_at = EXCLUDED.updated_at
                """, accountId, descriptionKey, kind.name(), categoryId, Timestamp.from(now));
    }
}
