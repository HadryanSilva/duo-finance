package br.com.hadryan.duo.finance.report.dto;

import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public class ReportDtos {

    // ── Summary ───────────────────────────────────────────────────────────────

    public record SummaryResponse(
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal totalIncome,
            BigDecimal totalExpense,
            BigDecimal balance,
            long transactionCount
    ) {}

    // ── By Category ───────────────────────────────────────────────────────────

    public record ByCategoryResponse(
            LocalDate startDate,
            LocalDate endDate,
            TransactionType type,
            BigDecimal total,
            List<CategoryBreakdown> categories
    ) {}

    public record CategoryBreakdown(
            TransactionCategory category,
            String categoryLabel,
            BigDecimal amount,
            double percentage
    ) {}

    // ── Monthly Comparison (6 meses — dashboard) ──────────────────────────────

    public record MonthlyComparisonResponse(
            List<MonthSummary> months
    ) {}

    public record MonthSummary(
            int year,
            int month,
            String monthLabel,
            BigDecimal totalIncome,
            BigDecimal totalExpense,
            BigDecimal balance
    ) {}

    // ── RF38: Balance History (12 meses) ──────────────────────────────────────

    public record BalanceHistoryResponse(
            List<MonthSummary> months,
            BigDecimal totalIncomeInPeriod,
            BigDecimal totalExpenseInPeriod,
            BigDecimal netBalanceInPeriod,
            BigDecimal bestMonthBalance,
            BigDecimal worstMonthBalance
    ) {}

    // ── RF39: Partner Comparison ──────────────────────────────────────────────

    public record PartnerComparisonResponse(
            LocalDate startDate,
            LocalDate endDate,
            PartnerSummary partner1,
            PartnerSummary partner2
    ) {}

    public record PartnerSummary(
            UUID userId,
            String firstName,
            String lastName,
            String avatarUrl,
            BigDecimal totalIncome,
            BigDecimal totalExpense,
            BigDecimal balance,
            List<CategoryBreakdown> topExpenseCategories
    ) {}
}