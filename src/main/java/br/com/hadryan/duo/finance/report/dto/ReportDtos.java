package br.com.hadryan.duo.finance.report.dto;

import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import br.com.hadryan.duo.finance.transaction.enums.TransactionType;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public class ReportDtos {

    // ── Summary ───────────────────────────────────────────────────────────────

    public record SummaryResponse(
            LocalDate startDate,
            LocalDate endDate,
            BigDecimal totalIncome,
            BigDecimal totalExpense,
            BigDecimal balance,            // totalIncome - totalExpense
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
            double percentage           // % em relação ao total do tipo
    ) {}

    // ── Monthly Comparison ────────────────────────────────────────────────────

    public record MonthlyComparisonResponse(
            List<MonthSummary> months
    ) {}

    public record MonthSummary(
            int year,
            int month,
            String monthLabel,         // ex: "Mar/25"
            BigDecimal totalIncome,
            BigDecimal totalExpense,
            BigDecimal balance
    ) {}
}
