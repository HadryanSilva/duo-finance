package br.dev.duofinance.imports.service;

import br.dev.duofinance.imports.service.PlanContext.MemoryKey;
import br.dev.duofinance.imports.service.PlanContext.RememberedCategory;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.Instant;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Finds a remembered category for a description sharing a prefix of whole words with a remembered one. */
public final class SimilarMemory {

    private static final int MIN_WORDS = 2;

    private static final Set<String> GENERIC_PREFIXES = Set.of(
            "transfer to",
            "transfer from",
            "payment from",
            "cash withdrawal",
            "to pocket",
            "transferencia para",
            "transferencia de",
            "pagamento de",
            "levantamento de");

    private SimilarMemory() {}

    /**
     * Longest common prefix of whole words (at least two, and not a generic prefix) among the memory entries of the
     * same kind, ignoring entries pointing to the default category; ties go to the most recently updated entry.
     */
    public static Optional<UUID> find(
            String descriptionKey,
            TransactionKind kind,
            Map<MemoryKey, RememberedCategory> memory,
            UUID defaultCategory) {
        String[] words = descriptionKey.split(" ");
        int bestLength = 0;
        Instant bestUpdatedAt = null;
        UUID best = null;
        for (Map.Entry<MemoryKey, RememberedCategory> entry : memory.entrySet()) {
            RememberedCategory remembered = entry.getValue();
            if (entry.getKey().kind() != kind || remembered.categoryId().equals(defaultCategory)) continue;
            int common = commonWords(words, entry.getKey().descriptionKey().split(" "));
            if (common < MIN_WORDS || GENERIC_PREFIXES.contains(String.join(" ", Arrays.copyOf(words, common)))) {
                continue;
            }
            if (common > bestLength
                    || (common == bestLength && remembered.updatedAt().isAfter(bestUpdatedAt))) {
                bestLength = common;
                bestUpdatedAt = remembered.updatedAt();
                best = remembered.categoryId();
            }
        }
        return Optional.ofNullable(best);
    }

    private static int commonWords(String[] a, String[] b) {
        int n = Math.min(a.length, b.length);
        int i = 0;
        while (i < n && a[i].equals(b[i])) i++;
        return i;
    }
}
