package br.dev.duofinance.imports.service;

import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Everything the planner needs besides the rows; loaded from the database by the caller. {@code existingFingerprints}
 * maps each stored import fingerprint to the kind of the transaction stored under it.
 */
public record PlanContext(
        String accountCurrency,
        Map<String, UUID> productMapping,
        Map<String, TransactionKind> existingFingerprints,
        Map<MemoryKey, UUID> memory,
        Map<TransactionKind, UUID> defaultCategories,
        Set<DuplicateKey> manualTransactions,
        String feeDescription) {

    public record MemoryKey(String descriptionKey, TransactionKind kind) {}

    public record DuplicateKey(LocalDate date, TransactionKind kind, long amountCents, UUID bankAccountId) {}
}
