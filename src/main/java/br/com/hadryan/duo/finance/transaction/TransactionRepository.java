package br.com.hadryan.duo.finance.transaction;

import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

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

    // ── Busca o pai de uma transação filho ────────────────────────────────────
    @Query("""
            SELECT t FROM transactions t
            JOIN FETCH t.user
            WHERE t.id        = :id
              AND t.deletedAt IS NULL
            """)
    Optional<Transaction> findByIdWithUser(@Param("id") UUID id);

    // ── Pais recorrentes ativos — usado pelo scheduler ────────────────────────
    @Query("""
            SELECT t FROM transactions t
            WHERE t.recurring = true
              AND t.deletedAt IS NULL
              AND t.parentTransaction IS NULL
              AND (t.recurrenceEndDate IS NULL OR t.recurrenceEndDate >= :today)
            """)
    List<Transaction> findActiveRecurringParents(@Param("today") LocalDate today);

    // ── Última data gerada para um pai ────────────────────────────────────────
    @Query("""
            SELECT MAX(t.date) FROM transactions t
            WHERE t.parentTransaction.id = :parentId
              AND t.deletedAt            IS NULL
            """)
    Optional<LocalDate> findLastChildDate(@Param("parentId") UUID parentId);

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

    // ── RF42/RF43: filhos a partir de uma data (inclusive) ────────────────────
    @Query("""
            SELECT t FROM transactions t
            WHERE t.parentTransaction.id = :parentId
              AND t.date                >= :fromDate
              AND t.deletedAt           IS NULL
            """)
    List<Transaction> findFutureChildren(
            @Param("parentId") UUID parentId,
            @Param("fromDate") LocalDate fromDate
    );

    // ── RF43: todos os filhos de um pai ───────────────────────────────────────
    @Query("""
            SELECT t FROM transactions t
            WHERE t.parentTransaction.id = :parentId
              AND t.deletedAt            IS NULL
            """)
    List<Transaction> findAllChildren(@Param("parentId") UUID parentId);

    // ── RF43: soft-delete em lote de filhos futuros ───────────────────────────
    @Modifying
    @Query("""
            UPDATE transactions t
            SET t.deletedAt = :now
            WHERE t.parentTransaction.id = :parentId
              AND t.date                >= :fromDate
              AND t.deletedAt           IS NULL
            """)
    void softDeleteFutureChildren(
            @Param("parentId") UUID parentId,
            @Param("fromDate") LocalDate fromDate,
            @Param("now")      LocalDateTime now
    );

    // ── RF43: soft-delete de todos os filhos ──────────────────────────────────
    @Modifying
    @Query("""
            UPDATE transactions t
            SET t.deletedAt = :now
            WHERE t.parentTransaction.id = :parentId
              AND t.deletedAt            IS NULL
            """)
    void softDeleteAllChildren(
            @Param("parentId") UUID parentId,
            @Param("now")      LocalDateTime now
    );

    // ── Métricas de negócio ───────────────────────────────────────────────────

    /**
     * Transações criadas hoje pelo campo createdAt (momento do registro),
     * independente da data financeira escolhida pelo usuário.
     * Exclui soft-deleted e filhos de recorrência gerados pelo scheduler.
     */
    @Query("""
            SELECT COUNT(t) FROM transactions t
            WHERE t.type                      = :type
              AND CAST(t.createdAt AS date)   = CURRENT_DATE
              AND t.deletedAt                 IS NULL
              AND t.parentTransaction         IS NULL
            """)
    long countCreatedTodayByType(@Param("type") TransactionType type);

    /** Total de transações ativas (não soft-deleted). */
    @Query("SELECT COUNT(t) FROM transactions t WHERE t.deletedAt IS NULL")
    long countActive();

    @Query("""
        SELECT t.date, t.description, t.amount
        FROM transactions t
        WHERE t.couple.id = :coupleId
          AND t.deletedAt IS NULL
        """)
    List<Object[]> findDeduplicationKeys(@Param("coupleId") UUID coupleId);
}