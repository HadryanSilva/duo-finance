package br.dev.duofinance.budget.dto;

import br.dev.duofinance.shared.finance.FinanceValidation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record CreateBudgetRequest(
        @NotNull UUID categoryId,

        @NotNull @Min(1) @Max(FinanceValidation.MAX_AMOUNT_CENTS)
        Long monthlyLimitCents) {}
