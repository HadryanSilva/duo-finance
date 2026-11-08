package br.dev.duofinance.bankaccount.repository;

import br.dev.duofinance.bankaccount.model.BankAccount;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

    Optional<BankAccount> findByIdAndAccountId(UUID id, UUID accountId);

    List<BankAccount> findByAccountIdAndIdIn(UUID accountId, Collection<UUID> ids);

    boolean existsByAccountId(UUID accountId);

    /** FOR SHARE: a recurring rule referencing the bank account serializes with its archiving. */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("select b from BankAccount b where b.id = :id and b.accountId = :accountId")
    Optional<BankAccount> findByIdAndAccountIdForShare(UUID id, UUID accountId);

    /** FOR UPDATE: archiving waits for rule writes that hold the row FOR SHARE. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from BankAccount b where b.id = :id and b.accountId = :accountId")
    Optional<BankAccount> findByIdAndAccountIdForUpdate(UUID id, UUID accountId);

    @Query("""
            select b from BankAccount b
            where b.accountId = :accountId and (:includeArchived = true or b.archivedAt is null)
            order by case when b.archivedAt is null then 0 else 1 end, b.name, b.id
            """)
    List<BankAccount> list(UUID accountId, boolean includeArchived);

    /**
     * Clears a member who leaves the account as holder of its bank accounts, so the remaining partner can keep
     * editing them; returns the number of rows changed.
     */
    @Modifying(flushAutomatically = true)
    @Query("update BankAccount b set b.holderUserId = null where b.accountId = :accountId and b.holderUserId = :userId")
    int clearHolder(@Param("accountId") UUID accountId, @Param("userId") UUID userId);
}
