package br.dev.duofinance.bankaccount.dto;

import br.dev.duofinance.bankaccount.model.BankAccount;
import br.dev.duofinance.bankaccount.model.BankAccountType;
import java.time.Instant;
import java.util.UUID;

public record BankAccountResponse(
        UUID id,
        String name,
        BankAccountType type,
        long initialBalanceCents,
        long balanceCents,
        UUID holderUserId,
        boolean archived,
        Instant createdAt,
        Instant updatedAt) {

    public static BankAccountResponse of(BankAccount b, long balanceCents) {
        return new BankAccountResponse(
                b.getId(),
                b.getName(),
                b.getType(),
                b.getInitialBalanceCents(),
                balanceCents,
                b.getHolderUserId(),
                b.isArchived(),
                b.getCreatedAt(),
                b.getUpdatedAt());
    }
}
