package br.dev.duofinance.goal.dto;

import br.dev.duofinance.shared.finance.FinanceValidation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import org.jspecify.annotations.Nullable;

public record UpdateGoalRequest(
        @NotBlank @Size(max = 100) String name,

        @NotNull @Min(1) @Max(FinanceValidation.MAX_AMOUNT_CENTS)
        Long targetCents,

        @Nullable LocalDate targetDate,

        @NotNull @Min(0) @Max(100)
        Integer monthlyPercent,

        @Nullable @Size(max = 100) String revolutPocket) {}
