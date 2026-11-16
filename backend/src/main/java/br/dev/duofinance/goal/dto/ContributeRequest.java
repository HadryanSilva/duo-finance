package br.dev.duofinance.goal.dto;

import br.dev.duofinance.shared.finance.FinanceValidation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.YearMonth;
import org.jspecify.annotations.Nullable;

/** A null amount means "use the suggestion": monthly percent of that month's salary, capped at what is remaining. */
public record ContributeRequest(
        @NotNull YearMonth month,

        @Nullable @Min(1) @Max(FinanceValidation.MAX_AMOUNT_CENTS)
        Long amountCents) {}
