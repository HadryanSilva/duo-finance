package br.com.hadryan.duo.finance.transaction;

import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    // ── Listagem paginada com filtros opcionais ───────────────────────────────
    @Query("""
            SELECT t FROM transactions t
            JOIN FETCH t.user
            WHERE t.couple.id  = :coupleId
              AND t.deletedAt  IS NULL
              AND (:category   IS NULL OR t.category  = :category)
              AND (:type       IS NULL OR t.type      = :type)
              AND (:userId     IS NULL OR t.user.id   = :userId)
              AND (:startDate  IS NULL OR t.date     >= :startDate)
              AND (:endDate    IS NULL OR t.date     <= :endDate)
            ORDER BY t.date DESC, t.createdAt DESC
            """)
    Page<Transaction> findAllWithFilters(
            @Param("coupleId")  UUID coupleId,
            @Param("category")  TransactionCategory category,
            @Param("type")      TransactionType type,
            @Param("userId")    UUID userId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate,
            Pageable pageable
    );

    // ── Busca única garantindo que pertence ao casal ──────────────────────────
    @Query("""
            SELECT t FROM transactions t
            JOIN FETCH t.user
            WHERE t.id        = :id
              AND t.couple.id = :coupleId
              AND t.deletedAt IS NULL
            """)
    Optional<Transaction> findByIdAndCoupleId(
            @Param("id")       UUID id,
            @Param("coupleId") UUID coupleId
    );

    // ── Pais recorrentes ativos — usado pelo scheduler ────────────────────────
    @Query("""
            SELECT t FROM transactions t
            WHERE t.recurring = true
              AND t.deletedAt IS NULL
              AND t.parentTransaction IS NULL
              AND (t.recurrenceEndDate IS NULL OR t.recurrenceEndDate >= :today)
            """)
    List<Transaction> findActiveRecurringParents(@Param("today") LocalDate today);

    // ── Idempotência do scheduler ─────────────────────────────────────────────
    @Query("""
            SELECT COUNT(t) > 0 FROM transactions t
            WHERE t.parentTransaction.id = :parentId
              AND t.date                 = :date
              AND t.deletedAt            IS NULL
            """)
    boolean existsChildForDate(
            @Param("parentId") UUID parentId,
            @Param("date")     LocalDate date
    );
}
