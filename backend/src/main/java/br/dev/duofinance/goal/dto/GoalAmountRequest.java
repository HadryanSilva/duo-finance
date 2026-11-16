package br.dev.duofinance.goal.dto;

import br.dev.duofinance.shared.finance.FinanceValidation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.jspecify.annotations.Nullable;

public record GoalAmountRequest(
        @NotNull @Min(1) @Max(FinanceValidation.MAX_AMOUNT_CENTS)
        Long amountCents,

        @Nullable @Size(max = 200) String note) {}
