package br.dev.duofinance.transaction.dto;

import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

public record TransactionResponse(
        UUID id,
        TransactionKind kind,
        long amountCents,
        LocalDate date,
        @Nullable String description,
        @Nullable CategoryRef category,
        @Nullable BankAccountRef bankAccount,
        @Nullable BankAccountRef destinationBankAccount,
        @Nullable AuthorRef createdBy,
        @Nullable UUID recurringRuleId,
        Instant createdAt,
        Instant updatedAt) {

    public record CategoryRef(UUID id, String name, String color, String icon, boolean archived) {}

    public record BankAccountRef(UUID id, String name, boolean archived) {}

    /** The author; the whole reference is null when the author's user was deleted. */
    public record AuthorRef(UUID userId, String displayName) {}
}
