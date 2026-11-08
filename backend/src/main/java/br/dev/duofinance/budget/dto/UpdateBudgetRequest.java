package br.dev.duofinance.budget.dto;

import br.dev.duofinance.shared.finance.FinanceValidation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** The category is immutable; to move a budget, delete it and create another. */
public record UpdateBudgetRequest(
        @NotNull @Min(1) @Max(FinanceValidation.MAX_AMOUNT_CENTS)
        Long monthlyLimitCents) {}
