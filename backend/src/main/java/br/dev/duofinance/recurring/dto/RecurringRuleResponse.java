package br.dev.duofinance.recurring.dto;

import br.dev.duofinance.recurring.model.Frequency;
import br.dev.duofinance.transaction.dto.TransactionResponse.AuthorRef;
import br.dev.duofinance.transaction.dto.TransactionResponse.BankAccountRef;
import br.dev.duofinance.transaction.dto.TransactionResponse.CategoryRef;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDate;
import java.util.UUID;

/** {@code finished} is true once the next run falls after the end date. */
public record RecurringRuleResponse(
        UUID id,
        TransactionKind kind,
        long amountCents,
        String description,
        CategoryRef category,
        BankAccountRef bankAccount,
        BankAccountRef destinationBankAccount,
        Frequency frequency,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate nextRunDate,
        boolean active,
        boolean finished,
        AuthorRef createdBy) {}
