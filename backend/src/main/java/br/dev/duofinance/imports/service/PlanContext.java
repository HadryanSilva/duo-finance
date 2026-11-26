package br.dev.duofinance.imports.service;

import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/**
 * Everything the planner needs besides the rows; loaded from the database by the caller. {@code existingFingerprints}
 * maps each stored import fingerprint to the kind of the transaction stored under it. {@code pocketsBankAccountId} is
 * the remembered other side of pocket moves when the file has no mapped savings product.
 */
public record PlanContext(
        String accountCurrency,
        Map<String, UUID> productMapping,
        Map<String, TransactionKind> existingFingerprints,
        Map<MemoryKey, UUID> memory,
        Map<TransactionKind, UUID> defaultCategories,
        Set<DuplicateKey> manualTransactions,
        String feeDescription,
        @Nullable UUID pocketsBankAccountId,
        List<GoalRef> activeGoals) {

    public record MemoryKey(String descriptionKey, TransactionKind kind) {}

    public record DuplicateKey(LocalDate date, TransactionKind kind, long amountCents, UUID bankAccountId) {}

    public record GoalRef(UUID id, String name, @Nullable String revolutPocket) {}
}
