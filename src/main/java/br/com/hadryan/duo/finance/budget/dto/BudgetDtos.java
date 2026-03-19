package br.com.hadryan.duo.finance.budget.dto;

import br.com.hadryan.duo.finance.goal.dto.GoalDtos;
import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.DecimalMin;

import java.math.BigDecimal;
import java.util.List;

public class BudgetDtos {

    // ── Requests ──────────────────────────────────────────────────────────────

    public record SetGlobalLimitRequest(
            @NotNull(message = "Limite global é obrigatório")
            @DecimalMin(value = "0.01", message = "Limite deve ser maior que zero")
            BigDecimal monthlyLimit
    ) {}

    public enum DistributionRule {
        RULE_50_30_20,
        PROPORTIONAL_HISTORICAL,
        EQUAL
    }

    public record DistributeRequest(
            @NotNull(message = "Regra de distribuição é obrigatória")
            DistributionRule rule
    ) {}

    /** Alocação de uma categoria na distribuição customizada. */
    public record CategoryPercentage(
            @NotNull(message = "Categoria é obrigatória")
            TransactionCategory category,

            @NotNull(message = "Percentual é obrigatório")
            @DecimalMin(value = "0.0",   inclusive = true,  message = "Percentual não pode ser negativo")
            @DecimalMax(value = "100.0", inclusive = true,  message = "Percentual não pode exceder 100%")
            BigDecimal percentage
    ) {}

    /**
     * Distribuição customizada: o usuário define o % de cada categoria.
     * A soma dos percentuais deve ser exatamente 100.
     */
    public record CustomDistributeRequest(
            @NotEmpty(message = "Informe ao menos uma categoria")
            @Valid
            List<CategoryPercentage> allocations
    ) {}

    // ── Responses ─────────────────────────────────────────────────────────────

    public record BudgetOverviewResponse(
            int year,
            int month,
            String monthLabel,
            BigDecimal globalLimit,
            BigDecimal totalBudgeted,
            BigDecimal totalSpent,
            BigDecimal totalRemaining,
            double globalPercentage,
            GoalDtos.AlertLevel globalAlert,
            List<CategoryBudgetItem> categories
    ) {}

    public record CategoryBudgetItem(
            TransactionCategory category,
            String categoryLabel,
            BigDecimal budgeted,
            BigDecimal spent,
            BigDecimal remaining,
            double percentage,
            double percentageOfTotal,
            GoalDtos.AlertLevel alertLevel
    ) {}

    public record BudgetComparisonResponse(
            List<MonthComparison> months
    ) {}

    public record MonthComparison(
            int year,
            int month,
            String monthLabel,
            BigDecimal totalBudgeted,
            BigDecimal totalSpent,
            BigDecimal balance,
            double adherencePercentage,
            boolean withinBudget
    ) {}

    public record DistributeResponse(
            DistributionRule rule,
            BigDecimal globalLimit,
            List<CategoryAllocation> allocations
    ) {}

    public record CategoryAllocation(
            TransactionCategory category,
            String categoryLabel,
            BigDecimal allocated,
            double percentage
    ) {}

    /** Resposta da distribuição customizada — sem campo rule (é customizada). */
    public record CustomDistributeResponse(
            BigDecimal globalLimit,
            List<CategoryAllocation> allocations
    ) {}
}