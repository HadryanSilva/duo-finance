package br.dev.duofinance.imports.repository;

import br.dev.duofinance.imports.model.ImportBatch;
import jakarta.persistence.LockModeType;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ImportBatchRepository extends JpaRepository<ImportBatch, UUID> {

    Page<ImportBatch> findByAccountId(UUID accountId, Pageable pageable);

    /** Row-locks the batch so two concurrent undos serialize and the second sees {@code undone_at}. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from ImportBatch b where b.id = :id and b.accountId = :accountId")
    Optional<ImportBatch> findByIdAndAccountIdForUpdate(@Param("id") UUID id, @Param("accountId") UUID accountId);
}
