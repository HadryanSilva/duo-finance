package br.dev.duofinance.imports.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.imports.service.PlanContext.MemoryKey;
import br.dev.duofinance.imports.service.PlanContext.RememberedCategory;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SimilarMemoryTest {

    private static final UUID DEFAULT = UUID.randomUUID();
    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");

    private static void put(
            Map<MemoryKey, RememberedCategory> m, String key, TransactionKind kind, UUID cat, Instant at) {
        m.put(new MemoryKey(key, kind), new RememberedCategory(cat, at));
    }

    private static Optional<UUID> find(String key, Map<MemoryKey, RememberedCategory> m) {
        return SimilarMemory.find(key, TransactionKind.EXPENSE, m, DEFAULT);
    }

    @Test
    void twoWordPrefix_matches() {
        UUID cat = UUID.randomUUID();
        Map<MemoryKey, RememberedCategory> m = new HashMap<>();
        put(m, "pingo doce lisboa", TransactionKind.EXPENSE, cat, T0);
        assertThat(find("pingo doce porto", m)).contains(cat);
    }

    @Test
    void oneWordPrefix_doesNotMatch() {
        Map<MemoryKey, RememberedCategory> m = new HashMap<>();
        put(m, "uber", TransactionKind.EXPENSE, UUID.randomUUID(), T0);
        assertThat(find("uber eats lisboa", m)).isEmpty();
    }

    @Test
    void genericPrefixOnly_doesNotMatch() {
        Map<MemoryKey, RememberedCategory> m = new HashMap<>();
        put(m, "transfer to joao", TransactionKind.EXPENSE, UUID.randomUUID(), T0);
        assertThat(find("transfer to maria", m)).isEmpty();
    }

    @Test
    void longestPrefixWins() {
        UUID shortCat = UUID.randomUUID();
        UUID longCat = UUID.randomUUID();
        Map<MemoryKey, RememberedCategory> m = new HashMap<>();
        put(m, "pingo doce", TransactionKind.EXPENSE, shortCat, T0.plusSeconds(100));
        put(m, "pingo doce porto centro", TransactionKind.EXPENSE, longCat, T0);
        assertThat(find("pingo doce porto norte", m)).contains(longCat);
    }

    @Test
    void tieBreak_mostRecent() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        Map<MemoryKey, RememberedCategory> m = new HashMap<>();
        put(m, "pingo doce lisboa", TransactionKind.EXPENSE, older, T0);
        put(m, "pingo doce braga", TransactionKind.EXPENSE, newer, T0.plusSeconds(60));
        assertThat(find("pingo doce porto", m)).contains(newer);
    }

    @Test
    void defaultCategoryEntries_ignored() {
        Map<MemoryKey, RememberedCategory> m = new HashMap<>();
        put(m, "pingo doce lisboa", TransactionKind.EXPENSE, DEFAULT, T0);
        assertThat(find("pingo doce porto", m)).isEmpty();
    }

    @Test
    void otherKind_ignored() {
        Map<MemoryKey, RememberedCategory> m = new HashMap<>();
        put(m, "pingo doce lisboa", TransactionKind.INCOME, UUID.randomUUID(), T0);
        assertThat(find("pingo doce porto", m)).isEmpty();
    }
}
