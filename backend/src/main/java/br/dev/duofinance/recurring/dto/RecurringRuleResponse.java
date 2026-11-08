package br.dev.duofinance.recurring.dto;

import br.dev.duofinance.recurring.model.Frequency;
import br.dev.duofinance.transaction.dto.TransactionResponse.AuthorRef;
import br.dev.duofinance.transaction.dto.TransactionResponse.BankAccountRef;
import br.dev.duofinance.transaction.dto.TransactionResponse.CategoryRef;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDate;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** {@code finished} is true once the next run falls after the end date. */
public record RecurringRuleResponse(
        UUID id,
        TransactionKind kind,
        long amountCents,
        @Nullable String description,
        @Nullable CategoryRef category,
        @Nullable BankAccountRef bankAccount,
        @Nullable BankAccountRef destinationBankAccount,
        Frequency frequency,
        LocalDate startDate,
        @Nullable LocalDate endDate,
        LocalDate nextRunDate,
        boolean active,
        boolean finished,
        @Nullable AuthorRef createdBy) {}
