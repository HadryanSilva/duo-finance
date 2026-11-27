package br.dev.duofinance.imports.service;

import br.dev.duofinance.imports.dto.PocketInfo;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * {@code startedAt} and {@code lineNumber} come from the file row the transaction was built from (for a transfer, its
 * negative leg when present); they order writes that follow the statement's sequence.
 */
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
        RowStatus status,
        @Nullable PocketInfo pocket,
        LocalDateTime startedAt,
        int lineNumber) {}
