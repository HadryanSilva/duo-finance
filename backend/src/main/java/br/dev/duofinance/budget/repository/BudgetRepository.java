package br.dev.duofinance.budget.repository;

import br.dev.duofinance.budget.model.Budget;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface BudgetRepository extends JpaRepository<Budget, UUID> {

    Optional<Budget> findByIdAndAccountId(UUID id, UUID accountId);

    List<Budget> findByAccountId(UUID accountId);

    boolean existsByAccountId(UUID accountId);
}
