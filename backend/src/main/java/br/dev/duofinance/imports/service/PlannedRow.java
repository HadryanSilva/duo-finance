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
        RowStatus status,
        SuggestionSource suggestionSource,
        boolean aiEligible) {

    /** True when the row may be sent to the AI and no memory-based suggestion was found. */
    public boolean needsAi() {
        return aiEligible
                && (suggestionSource == SuggestionSource.DEFAULT || suggestionSource == SuggestionSource.NONE);
    }
}
