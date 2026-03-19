package br.com.hadryan.duo.finance.budget.dto;

import br.com.hadryan.duo.finance.goal.dto.GoalDtos;
import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

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
        /** 50% necessidades, 30% desejos, 20% poupança/investimento */
        RULE_50_30_20,
        /** Distribui proporcionalmente ao gasto histórico dos últimos 3 meses */
        PROPORTIONAL_HISTORICAL,
        /** Divide igualmente entre todas as categorias com meta ativa */
        EQUAL
    }

    public record DistributeRequest(
            @NotNull(message = "Regra de distribuição é obrigatória")
            DistributionRule rule
    ) {}

    // ── Responses ─────────────────────────────────────────────────────────────

    /**
     * Visão consolidada do orçamento do mês.
     */
    public record BudgetOverviewResponse(
            int year,
            int month,
            String monthLabel,
            BigDecimal globalLimit,          // null se não definido
            BigDecimal totalBudgeted,        // soma dos limits das goals ativas
            BigDecimal totalSpent,           // total gasto no mês
            BigDecimal totalRemaining,       // globalLimit - totalSpent (ou totalBudgeted - totalSpent)
            double globalPercentage,         // % do limite global consumido
            GoalDtos.AlertLevel globalAlert,
            List<CategoryBudgetItem> categories
    ) {}

    /**
     * Item de categoria no orçamento — orçado vs realizado.
     */
    public record CategoryBudgetItem(
            TransactionCategory category,
            String categoryLabel,
            BigDecimal budgeted,             // limite da meta
            BigDecimal spent,                // gasto real no mês
            BigDecimal remaining,
            double percentage,               // spent / budgeted
            double percentageOfTotal,        // spent / totalSpent (participação no gasto)
            GoalDtos.AlertLevel alertLevel
    ) {}

    /**
     * Comparação orçado vs realizado por mês.
     */
    public record BudgetComparisonResponse(
            List<MonthComparison> months
    ) {}

    public record MonthComparison(
            int year,
            int month,
            String monthLabel,
            BigDecimal totalBudgeted,
            BigDecimal totalSpent,
            BigDecimal balance,              // budgeted - spent (positivo = sobrou, negativo = estourou)
            double adherencePercentage,      // spent / budgeted * 100
            boolean withinBudget
    ) {}

    /**
     * Resultado da distribuição automática.
     */
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
}