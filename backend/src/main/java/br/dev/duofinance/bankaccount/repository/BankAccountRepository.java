package br.dev.duofinance.bankaccount.repository;

import br.dev.duofinance.bankaccount.model.BankAccount;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface BankAccountRepository extends JpaRepository<BankAccount, UUID> {

    Optional<BankAccount> findByIdAndAccountId(UUID id, UUID accountId);

    boolean existsByAccountId(UUID accountId);

    @Query("""
            select b from BankAccount b
            where b.accountId = :accountId and (:includeArchived = true or b.archivedAt is null)
            order by case when b.archivedAt is null then 0 else 1 end, b.name, b.id
            """)
    List<BankAccount> list(UUID accountId, boolean includeArchived);
}
