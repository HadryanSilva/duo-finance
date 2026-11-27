package br.dev.duofinance.imports.service;

import br.dev.duofinance.imports.repository.CategoryMemoryRepository;
import br.dev.duofinance.imports.service.PlanContext.DuplicateKey;
import br.dev.duofinance.imports.service.PlanContext.GoalRef;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** Loads the database state (the default category per kind is the catch-all "Outros"/"Other" or "Outras receitas"/"Other income") the planner needs; shared by preview and commit. */
@Component
public class ImportContextLoader {

    static final String DEFAULT_PRODUCT = "Current";

    private final JdbcTemplate jdbc;
    private final CategoryMemoryRepository memory;

    public ImportContextLoader(JdbcTemplate jdbc, CategoryMemoryRepository memory) {
        this.jdbc = jdbc;
        this.memory = memory;
    }

    /**
     * The effective product mapping: the given one, plus {@code Current -> bankAccountId} unless given. Savings
     * products are never mapped: their rows belong to the statement's own account, so a mapping given for one is
     * ignored.
     */
    static Map<String, UUID> effectiveMapping(UUID bankAccountId, @Nullable Map<String, UUID> productMapping) {
        Map<String, UUID> mapping = new LinkedHashMap<>();
        if (productMapping != null) {
            mapping.putAll(productMapping);
        }
        if (!mapping.containsKey(DEFAULT_PRODUCT)) {
            mapping.put(DEFAULT_PRODUCT, bankAccountId);
        }
        mapping.keySet().removeIf(PocketRows::isSavingsProduct);
        return mapping;
    }

    /**
     * Already imported fingerprints come from transactions and from imported goal movements (pocket moves, which have
     * no transaction); the latter count as TRANSFER, so a later statement pairs the move's legs and marks it imported.
     */
    public PlanContext load(
            UUID accountId,
            UUID userId,
            UUID bankAccountId,
            Map<String, UUID> productMapping,
            List<RevolutRow> rows) {
        Map<String, UUID> mapping = effectiveMapping(bankAccountId, productMapping);
        String currency = jdbc.queryForObject("SELECT currency FROM accounts WHERE id = ?", String.class, accountId)
                .trim();
        String locale = jdbc.queryForObject("SELECT locale FROM users WHERE id = ?", String.class, userId);
        String feeDescription = "en".equals(locale) ? "Revolut fee" : "Tarifa Revolut";

        Map<String, TransactionKind> fingerprints = new HashMap<>();
        jdbc.query(
                "SELECT import_fingerprint, kind FROM transactions"
                        + " WHERE account_id = ? AND import_fingerprint IS NOT NULL",
                rs -> {
                    fingerprints.put(
                            rs.getString("import_fingerprint"), TransactionKind.valueOf(rs.getString("kind")));
                },
                accountId);
        jdbc.query(
                "SELECT import_fingerprint FROM goal_movements WHERE account_id = ? AND import_fingerprint IS NOT NULL",
                rs -> {
                    fingerprints.put(rs.getString("import_fingerprint"), TransactionKind.TRANSFER);
                },
                accountId);

        return new PlanContext(
                currency,
                mapping,
                fingerprints,
                memory.findActive(accountId),
                defaultCategories(accountId),
                manualTransactions(accountId, mapping.values(), rows),
                feeDescription,
                activeGoals(accountId));
    }

    private List<GoalRef> activeGoals(UUID accountId) {
        return jdbc.query(
                "SELECT id, name, revolut_pocket FROM goals WHERE account_id = ? AND completed_at IS NULL"
                        + " ORDER BY created_at, id",
                (rs, n) -> new GoalRef(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("revolut_pocket")),
                accountId);
    }

    private Map<TransactionKind, UUID> defaultCategories(UUID accountId) {
        Map<TransactionKind, UUID> out = new HashMap<>();
        jdbc.query(
                """
                SELECT kind, id FROM categories
                WHERE account_id = ? AND system_default AND archived_at IS NULL
                  AND name IN ('Outros', 'Other', 'Outras receitas', 'Other income')
                ORDER BY name
                """,
                rs -> {
                    out.putIfAbsent(TransactionKind.valueOf(rs.getString("kind")), rs.getObject("id", UUID.class));
                },
                accountId);
        return out;
    }

    private Set<DuplicateKey> manualTransactions(UUID accountId, Collection<UUID> mapped, List<RevolutRow> rows) {
        Set<UUID> accounts = new HashSet<>(mapped);
        accounts.remove(null);
        LocalDate min = null;
        LocalDate max = null;
        for (RevolutRow row : rows) {
            // From the purchase day: the planner matches manual transactions from Started Date to the stored date.
            LocalDate started = row.startedAt().toLocalDate();
            LocalDate date = (row.completedAt() != null ? row.completedAt() : row.startedAt()).toLocalDate();
            if (min == null || started.isBefore(min)) {
                min = started;
            }
            if (max == null || date.isAfter(max)) {
                max = date;
            }
        }
        Set<DuplicateKey> out = new HashSet<>();
        if (accounts.isEmpty() || min == null) {
            return out;
        }
        String placeholders = String.join(",", accounts.stream().map(a -> "?").toList());
        List<Object> args = new ArrayList<>();
        args.add(accountId);
        args.add(min);
        args.add(max);
        args.addAll(accounts);
        jdbc.query(
                "SELECT date, kind, amount_cents, bank_account_id FROM transactions"
                        + " WHERE account_id = ? AND import_fingerprint IS NULL AND date BETWEEN ? AND ?"
                        + " AND bank_account_id IN (" + placeholders + ")",
                rs -> {
                    out.add(new DuplicateKey(
                            rs.getObject("date", LocalDate.class),
                            TransactionKind.valueOf(rs.getString("kind")),
                            rs.getLong("amount_cents"),
                            rs.getObject("bank_account_id", UUID.class)));
                },
                args.toArray());
        return out;
    }
}
