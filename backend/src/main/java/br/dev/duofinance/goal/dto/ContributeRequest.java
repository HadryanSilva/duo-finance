package br.dev.duofinance.goal.dto;

import br.dev.duofinance.shared.finance.FinanceValidation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

/** A null date means today in the server clock; the contribution counts in the month of its date. */
public record ContributeRequest(
        @NotNull @Positive @Max(FinanceValidation.MAX_AMOUNT_CENTS)
        Long amountCents,

        @Nullable LocalDate date,

        @Nullable @Size(max = 200) String note) {}
