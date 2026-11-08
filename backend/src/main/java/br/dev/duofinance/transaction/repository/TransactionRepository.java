package br.dev.duofinance.transaction.repository;

import br.dev.duofinance.transaction.model.Transaction;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    Optional<Transaction> findByIdAndAccountId(UUID id, UUID accountId);

    boolean existsByAccountId(UUID accountId);
}
