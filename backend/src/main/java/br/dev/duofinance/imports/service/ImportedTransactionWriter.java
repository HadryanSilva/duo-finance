package br.dev.duofinance.imports.service;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.engine.spi.SharedSessionContractImplementor;
import org.hibernate.id.uuid.UuidVersion7Strategy;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Inserts one imported transaction. The unique fingerprint index with {@code ON CONFLICT DO NOTHING} makes a row
 * already written by a concurrent commit a no-op instead of an error.
 */
@Component
public class ImportedTransactionWriter {

    private static final String INSERT = """
            INSERT INTO transactions (id, account_id, kind, amount_cents, date, description, category_id,
                                      bank_account_id, destination_bank_account_id, created_by,
                                      created_at, updated_at, import_batch_id, import_fingerprint)
            VALUES (:id, :accountId, :kind, :amount, :date, :description, :categoryId,
                    :bankAccountId, :destinationId, :userId, :now, :now, :batchId, :fingerprint)
            ON CONFLICT (account_id, import_fingerprint) WHERE import_fingerprint IS NOT NULL DO NOTHING
            """;

    @PersistenceContext
    private EntityManager entityManager;

    /** Returns the new transaction's id, or null when the fingerprint already exists. */
    @Transactional(propagation = Propagation.MANDATORY)
    public @Nullable UUID insert(
            UUID accountId, UUID userId, UUID batchId, PlannedRow row, UUID categoryId, Instant now) {
        SharedSessionContractImplementor session = entityManager.unwrap(SharedSessionContractImplementor.class);
        UUID id = UuidVersion7Strategy.INSTANCE.generateUuid(session);
        int inserted = entityManager
                .createNativeQuery(INSERT)
                .setParameter("id", id)
                .setParameter("accountId", accountId)
                .setParameter("kind", row.kind().name())
                .setParameter("amount", row.amountCents())
                .setParameter("date", row.date())
                .setParameter("description", row.description())
                .setParameter("categoryId", categoryId)
                .setParameter("bankAccountId", row.bankAccountId())
                .setParameter("destinationId", row.destinationBankAccountId())
                .setParameter("userId", userId)
                .setParameter("now", now)
                .setParameter("batchId", batchId)
                .setParameter("fingerprint", row.rowId())
                .executeUpdate();
        return inserted == 1 ? id : null;
    }
}
