package br.dev.duofinance.recurring.repository;

import br.dev.duofinance.recurring.model.RecurringRule;
import jakarta.persistence.LockModeType;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface RecurringRuleRepository extends JpaRepository<RecurringRule, UUID> {

    Optional<RecurringRule> findByIdAndAccountId(UUID id, UUID accountId);

    boolean existsByAccountId(UUID accountId);

    /** Active rules whose next run is due; unlocked, so callers must re-check under the lock. */
    @Query("select r.id from RecurringRule r where r.active = true and r.nextRunDate <= :today order by r.id")
    List<UUID> findDueRuleIds(LocalDate today);

    @Query("select r.accountId from RecurringRule r where r.id = :id")
    Optional<UUID> findAccountId(UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecurringRule r where r.id = :id")
    Optional<RecurringRule> findByIdForUpdate(UUID id);

    /** FOR UPDATE, scoped to the account: rule writes serialize with generateDue. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RecurringRule r where r.id = :id and r.accountId = :accountId")
    Optional<RecurringRule> findByIdAndAccountIdForUpdate(UUID id, UUID accountId);

    @Query("""
            select r from RecurringRule r
            where r.accountId = :accountId
            order by r.active desc, r.nextRunDate asc, r.id asc
            """)
    List<RecurringRule> list(UUID accountId);

    @Query("select r from RecurringRule r where r.accountId = :accountId and r.active = true")
    List<RecurringRule> findActive(UUID accountId);

    /** An active, not finished rule uses the category. */
    @Query("""
            select count(r) > 0 from RecurringRule r
            where r.categoryId = :categoryId and r.active = true
              and (r.endDate is null or r.nextRunDate <= r.endDate)
            """)
    boolean activeRuleUsesCategory(UUID categoryId);

    /** An active, not finished rule uses the bank account as origin or destination. */
    @Query("""
            select count(r) > 0 from RecurringRule r
            where (r.bankAccountId = :bankAccountId or r.destinationBankAccountId = :bankAccountId)
              and r.active = true
              and (r.endDate is null or r.nextRunDate <= r.endDate)
            """)
    boolean activeRuleUsesBankAccount(UUID bankAccountId);
}
