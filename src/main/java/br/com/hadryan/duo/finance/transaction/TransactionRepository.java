package br.com.hadryan.duo.finance.transaction;

import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID>, JpaSpecificationExecutor<Transaction> {

    // findAllWithFilters removido — filtros dinâmicos agora via Specification (TransactionService)

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


    // ── Última data gerada para um pai — ancoragem do backfill ────────────

    /**
     * Retorna a data do filho mais recente de um pai.
     * Usada pelo backfill para saber de onde retomar sem reprocessar o que já existe.
     */
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
}