package br.dev.duofinance.transaction.repository;

import br.dev.duofinance.transaction.model.Transaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    Optional<Transaction> findByIdAndAccountId(UUID id, UUID accountId);

    boolean existsByAccountId(UUID accountId);
}
