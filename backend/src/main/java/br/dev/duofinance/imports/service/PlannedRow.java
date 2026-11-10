package br.dev.duofinance.imports.service;

import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDate;
import java.util.UUID;

public record PlannedRow(
        String rowId,
        LocalDate date,
        TransactionKind kind,
        long amountCents,
        String description,
        String descriptionKey,
        UUID bankAccountId,
        UUID destinationBankAccountId,
        UUID suggestedCategoryId,
        RowStatus status) {}
