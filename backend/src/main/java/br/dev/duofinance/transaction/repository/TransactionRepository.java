package br.dev.duofinance.transaction.repository;

import br.dev.duofinance.transaction.model.Transaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByIdAndAccountId(UUID id, UUID accountId);

    boolean existsByAccountId(UUID accountId);

    /** Deletes every transaction the given import batch created, including ones edited since; returns the count. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(
            value = "DELETE FROM transactions WHERE account_id = :accountId AND import_batch_id = :batchId",
            nativeQuery = true)
    int deleteByImportBatch(@Param("accountId") UUID accountId, @Param("batchId") UUID batchId);
}
