package br.com.hadryan.duo.finance.budget;

import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, UUID> {

    List<Budget> findByCoupleId(UUID coupleId);

    Optional<Budget> findByCoupleIdAndCategory(UUID coupleId, TransactionCategory category);

    void deleteByCoupleIdAndCategory(UUID coupleId, TransactionCategory category);

    void deleteByCoupleId(UUID coupleId);
}