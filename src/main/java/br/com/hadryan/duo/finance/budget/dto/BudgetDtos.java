package br.com.hadryan.duo.finance.budget.dto;

import br.com.hadryan.duo.finance.transaction.enums.TransactionCategory;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.util.List;

public class BudgetDtos {

    // ── Requests ──────────────────────────────────────────────────────────────

    public record SetIncomeRequest(
            @NotNull(message = "Renda mensal é obrigatória")
            @DecimalMin(value = "0.01", message = "Renda deve ser maior que zero")
            BigDecimal monthlyIncome
    ) {}

    public record CategoryAllocationRequest(
            @NotNull(message = "Categoria é obrigatória")
            TransactionCategory category,

            @NotNull(message = "Percentual é obrigatório")
            @DecimalMin(value = "0.01", inclusive = true,  message = "Percentual deve ser maior que zero")
            @DecimalMax(value = "100.0", inclusive = true, message = "Percentual não pode exceder 100%")
            BigDecimal percentage
    ) {}

    public record SaveBudgetRequest(
            @NotEmpty(message = "Informe ao menos uma categoria")
            @Valid
            List<CategoryAllocationRequest> allocations
    ) {}

    // ── Responses ─────────────────────────────────────────────────────────────

    /**
     * Visão completa do orçamento: renda, alocações e realizado do mês.
     */
    public record BudgetOverviewResponse(
            BigDecimal monthlyIncome,         // renda base — null se não informada
            BigDecimal totalAllocated,        // soma dos valores calculados (income * % / 100)
            BigDecimal totalAllocatedPct,     // soma dos percentuais cadastrados
            BigDecimal totalSpent,            // total gasto no mês
            BigDecimal totalRemaining,        // totalAllocated - totalSpent
            int year,
            int month,
            String monthLabel,
            List<CategoryBudgetItem> categories
    ) {}

    public record CategoryBudgetItem(
            TransactionCategory category,
            String categoryLabel,
            BigDecimal percentage,            // % da renda alocado
            BigDecimal allocated,             // valor em R$ = income * percentage / 100
            BigDecimal spent,                 // gasto real no mês
            BigDecimal remaining,             // allocated - spent
            double usagePercentage,           // spent / allocated * 100
            BudgetStatus status
    ) {}

    public enum BudgetStatus {
        OK,        // <= 80%
        WARNING,   // > 80% e <= 100%
        EXCEEDED   // > 100%
    }

    /**
     * Comparação orçado vs realizado por mês.
     */
    public record BudgetComparisonResponse(
            BigDecimal monthlyIncome,
            List<MonthComparison> months
    ) {}

    public record MonthComparison(
            int year,
            int month,
            String monthLabel,
            BigDecimal totalAllocated,
            BigDecimal totalSpent,
            BigDecimal balance,
            double adherencePercentage,
            boolean withinBudget
    ) {}

    /**
     * Item simples de alocação — usado para listagem e edição.
     */
    public record BudgetAllocationResponse(
            TransactionCategory category,
            String categoryLabel,
            BigDecimal percentage,
            BigDecimal allocated     // calculado: income * percentage / 100
    ) {}
}