package br.com.hadryan.duo.finance.category;

import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface CustomCategoryRepository extends JpaRepository<CustomCategory, UUID> {

    List<CustomCategory> findByCoupleIdAndActiveTrue(UUID coupleId);

    List<CustomCategory> findByCoupleId(UUID coupleId);

    Optional<CustomCategory> findByIdAndCoupleId(UUID id, UUID coupleId);

    boolean existsByCoupleIdAndNameIgnoreCase(UUID coupleId, String name);

    boolean existsByCoupleIdAndNameIgnoreCaseAndIdNot(UUID coupleId, String name, UUID excludeId);

    // Verifica se há transações vinculadas antes de excluir (RF31 — bloquear exclusão)
    @Query("""
            SELECT COUNT(t) > 0
            FROM transactions t
            WHERE t.customCategory.id = :categoryId
              AND t.deletedAt IS NULL
            """)
    boolean hasActiveTransactions(@Param("categoryId") UUID categoryId);

    List<CustomCategory> findByCoupleIdAndType(UUID coupleId, TransactionType type);
}