package br.dev.duofinance.recurring.dto;

import br.dev.duofinance.shared.finance.FinanceValidation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.UUID;

/** Kind, frequency and start date are fixed; changing them means creating another rule. */
public record UpdateRecurringRuleRequest(
        @NotNull @Min(1) @Max(FinanceValidation.MAX_AMOUNT_CENTS)
        Long amountCents,

        @Size(max = 140) String description,
        UUID categoryId,
        UUID bankAccountId,
        UUID destinationBankAccountId,
        LocalDate endDate) {

    /** Surrounding whitespace is not an error; a blank description becomes null. */
    public UpdateRecurringRuleRequest {
        description = FinanceValidation.normalizeDescription(description);
    }
}
