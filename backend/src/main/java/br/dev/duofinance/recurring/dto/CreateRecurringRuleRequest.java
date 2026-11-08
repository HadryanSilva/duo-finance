package br.dev.duofinance.recurring.dto;

import br.dev.duofinance.recurring.model.Frequency;
import br.dev.duofinance.shared.finance.FinanceValidation;
import br.dev.duofinance.transaction.model.TransactionKind;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

public record CreateRecurringRuleRequest(
        @NotNull TransactionKind kind,

        @NotNull @Min(1) @Max(FinanceValidation.MAX_AMOUNT_CENTS)
        Long amountCents,

        @Size(max = 140) String description,
        UUID categoryId,
        UUID bankAccountId,
        UUID destinationBankAccountId,
        @NotNull Frequency frequency,
        @NotNull LocalDate startDate,
        LocalDate endDate) {

    /** Surrounding whitespace is not an error; a blank description becomes null. */
    public CreateRecurringRuleRequest {
        description = FinanceValidation.normalizeDescription(description);
    }
}
