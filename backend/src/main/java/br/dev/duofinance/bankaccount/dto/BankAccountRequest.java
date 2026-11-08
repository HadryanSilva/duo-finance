package br.dev.duofinance.bankaccount.dto;

import br.dev.duofinance.bankaccount.model.BankAccountType;
import br.dev.duofinance.shared.finance.FinanceValidation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record BankAccountRequest(
        @NotBlank @Size(max = 60) String name,
        @NotNull BankAccountType type,

        @NotNull @Min(-FinanceValidation.MAX_AMOUNT_CENTS) @Max(FinanceValidation.MAX_AMOUNT_CENTS)
        Long initialBalanceCents,

        UUID holderUserId) {

    /** Surrounding whitespace is not an error; trim before validation runs. */
    public BankAccountRequest {
        name = FinanceValidation.trimOrNull(name);
    }
}
