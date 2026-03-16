package br.com.hadryan.duo.finance.goal;

import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface GoalRepository extends JpaRepository<Goal, UUID> {

    @Query("""
            SELECT g FROM goals g
            WHERE g.couple.id = :coupleId
              AND g.active    = true
            """)
    List<Goal> findActiveByCoupleId(@Param("coupleId") UUID coupleId);

    @Query("""
            SELECT g FROM goals g
            WHERE g.couple.id = :coupleId
            """)
    List<Goal> findAllByCoupleId(@Param("coupleId") UUID coupleId);

    @Query("""
            SELECT g FROM goals g
            WHERE g.id        = :id
              AND g.couple.id = :coupleId
            """)
    Optional<Goal> findByIdAndCoupleId(
            @Param("id")       UUID id,
            @Param("coupleId") UUID coupleId
    );

    @Query("""
            SELECT g FROM goals g
            WHERE g.couple.id = :coupleId
              AND g.category  = :category
            """)
    Optional<Goal> findByCoupleIdAndCategory(
            @Param("coupleId") UUID coupleId,
            @Param("category") TransactionCategory category
    );

    @Query("""
            SELECT COUNT(g) > 0 FROM goals g
            WHERE g.couple.id = :coupleId
              AND g.category  = :category
            """)
    boolean existsByCoupleIdAndCategory(
            @Param("coupleId") UUID coupleId,
            @Param("category") TransactionCategory category
    );
}