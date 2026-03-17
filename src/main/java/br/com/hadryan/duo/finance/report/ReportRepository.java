package br.com.hadryan.duo.finance.report;

import br.com.hadryan.duo.finance.report.dto.CategorySumProjection;
import br.com.hadryan.duo.finance.report.dto.MonthlySumProjection;
import br.com.hadryan.duo.finance.report.dto.ReportDtos;
import br.com.hadryan.duo.finance.transaction.Transaction;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@Repository
public interface ReportRepository extends JpaRepository<Transaction, UUID> {

    // ── Summary: totais de receita e despesa no período ───────────────────────

    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM transactions t
            WHERE t.couple.id = :coupleId
              AND t.type      = :type
              AND t.date     >= :startDate
              AND t.date     <= :endDate
              AND t.deletedAt IS NULL
            """)
    BigDecimal sumByType(
            @Param("coupleId")  UUID coupleId,
            @Param("type")      TransactionType type,
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate
    );

    @Query("""
            SELECT COUNT(t)
            FROM transactions t
            WHERE t.couple.id = :coupleId
              AND t.date     >= :startDate
              AND t.date     <= :endDate
              AND t.deletedAt IS NULL
            """)
    long countInPeriod(
            @Param("coupleId")  UUID coupleId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate
    );

    // ── By Category: total agrupado por categoria ─────────────────────────────

    @Query("""
            SELECT new br.com.hadryan.duo.finance.report.dto.CategorySumProjection(
                t.category,
                SUM(t.amount)
            )
            FROM transactions t
            WHERE t.couple.id = :coupleId
              AND t.type      = :type
              AND t.date     >= :startDate
              AND t.date     <= :endDate
              AND t.deletedAt IS NULL
            GROUP BY t.category
            ORDER BY SUM(t.amount) DESC
            """)
    List<CategorySumProjection> sumByCategory(
            @Param("coupleId")  UUID coupleId,
            @Param("type")      TransactionType type,
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate
    );

    // ── Monthly Comparison ────────────────────────────────────────────────────

    @Query("""
            SELECT new br.com.hadryan.duo.finance.report.dto.MonthlySumProjection(
                YEAR(t.date),
                MONTH(t.date),
                t.type,
                SUM(t.amount)
            )
            FROM transactions t
            WHERE t.couple.id = :coupleId
              AND t.date     >= :startDate
              AND t.date     <= :endDate
              AND t.deletedAt IS NULL
            GROUP BY YEAR(t.date), MONTH(t.date), t.type
            ORDER BY YEAR(t.date), MONTH(t.date)
            """)
    List<MonthlySumProjection> sumByMonth(
            @Param("coupleId")  UUID coupleId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate
    );

    // ── CSV Export ────────────────────────────────────────────────────────────

    @Query("""
            SELECT t FROM transactions t
            JOIN FETCH t.user
            WHERE t.couple.id = :coupleId
              AND t.date     >= :startDate
              AND t.date     <= :endDate
              AND t.deletedAt IS NULL
            ORDER BY t.date DESC, t.createdAt DESC
            """)
    List<Transaction> findAllForExport(
            @Param("coupleId")  UUID coupleId,
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate
    );

    // ── RF39: Partner Comparison — totais por usuário ─────────────────────────

    @Query("""
            SELECT COALESCE(SUM(t.amount), 0)
            FROM transactions t
            WHERE t.couple.id = :coupleId
              AND t.user.id   = :userId
              AND t.type      = :type
              AND t.date     >= :startDate
              AND t.date     <= :endDate
              AND t.deletedAt IS NULL
            """)
    BigDecimal sumByTypeAndUser(
            @Param("coupleId")  UUID coupleId,
            @Param("userId")    UUID userId,
            @Param("type")      TransactionType type,
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate
    );

    @Query("""
            SELECT new br.com.hadryan.duo.finance.report.dto.CategorySumProjection(
                t.category,
                SUM(t.amount)
            )
            FROM transactions t
            WHERE t.couple.id = :coupleId
              AND t.user.id   = :userId
              AND t.type      = :type
              AND t.date     >= :startDate
              AND t.date     <= :endDate
              AND t.deletedAt IS NULL
            GROUP BY t.category
            ORDER BY SUM(t.amount) DESC
            """)
    List<CategorySumProjection> sumByCategoryAndUser(
            @Param("coupleId")  UUID coupleId,
            @Param("userId")    UUID userId,
            @Param("type")      TransactionType type,
            @Param("startDate") LocalDate startDate,
            @Param("endDate")   LocalDate endDate
    );
}