package br.dev.duofinance.imports.service;

import br.dev.duofinance.imports.service.PlanContext.DuplicateKey;
import br.dev.duofinance.imports.service.PlanContext.MemoryKey;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Pure rule engine: parsed Revolut rows plus a context in, planned transactions and skipped rows out. */
public final class ImportPlanner {

    private static final int MAX_DESCRIPTION = 140;

    private ImportPlanner() {}

    public static ImportPlan plan(List<RevolutRow> rows, PlanContext ctx) {
        List<String> fingerprints = RowFingerprints.of(rows);
        LinkedHashSet<String> products = new LinkedHashSet<>();
        List<SkippedRow> skipped = new ArrayList<>();
        List<Integer> importable = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            RevolutRow row = rows.get(i);
            products.add(row.product());
            SkipReason reason = skipReason(row, ctx);
            if (reason == null) {
                importable.add(i);
            } else {
                skipped.add(new SkippedRow(
                        row.lineNumber(), dateOf(row), row.description(), row.amountRaw(), row.currency(), reason));
            }
        }

        int[] partner = pairTransfers(rows, importable, fingerprints, ctx);

        List<PlannedRow> planned = new ArrayList<>();
        for (int i : importable) {
            int p = partner[i];
            if (p != -1 && p < i) continue; // already emitted together with its partner
            RevolutRow row = rows.get(i);
            if (p == -1) {
                TransactionKind kind = row.amountCents() < 0 ? TransactionKind.EXPENSE : TransactionKind.INCOME;
                planned.add(build(fingerprints.get(i), row, kind, Math.abs(row.amountCents()), account(row, ctx),
                        null, row.description(), ctx));
                addFee(planned, fingerprints.get(i), row, ctx);
            } else {
                int neg = row.amountCents() < 0 ? i : p;
                int pos = neg == i ? p : i;
                RevolutRow negRow = rows.get(neg);
                RevolutRow posRow = rows.get(pos);
                planned.add(build(fingerprints.get(neg), negRow, TransactionKind.TRANSFER,
                        Math.abs(negRow.amountCents()), account(negRow, ctx), account(posRow, ctx),
                        negRow.description(), ctx));
                addFee(planned, fingerprints.get(neg), negRow, ctx);
                addFee(planned, fingerprints.get(pos), posRow, ctx);
            }
        }
        return new ImportPlan(List.copyOf(products), planned, skipped);
    }

    /**
     * Pairs opposite-sign rows with the same Started Date on different mapped accounts, first match in file order. A
     * pair whose negative leg is stored as a TRANSFER is the already imported transfer; a pair with either leg stored
     * otherwise (INCOME/EXPENSE: the mapping changed between imports) is not paired, so each leg keeps its own status.
     */
    private static int[] pairTransfers(
            List<RevolutRow> rows, List<Integer> importable, List<String> fingerprints, PlanContext ctx) {
        int[] partner = new int[rows.size()];
        Arrays.fill(partner, -1);
        for (int a = 0; a < importable.size(); a++) {
            int i = importable.get(a);
            if (partner[i] != -1) continue;
            RevolutRow ri = rows.get(i);
            for (int b = a + 1; b < importable.size(); b++) {
                int j = importable.get(b);
                RevolutRow rj = rows.get(j);
                if (partner[j] == -1
                        && rj.startedRaw().equals(ri.startedRaw())
                        && rj.amountCents() == -ri.amountCents()
                        && !account(ri, ctx).equals(account(rj, ctx))
                        && pairable(fingerprints.get(i), ri, fingerprints.get(j), ctx)) {
                    partner[i] = j;
                    partner[j] = i;
                    break;
                }
            }
        }
        return partner;
    }

    private static boolean pairable(String fpI, RevolutRow ri, String fpJ, PlanContext ctx) {
        Map<String, TransactionKind> stored = ctx.existingFingerprints();
        String negative = ri.amountCents() < 0 ? fpI : fpJ;
        if (stored.get(negative) == TransactionKind.TRANSFER) return true;
        return !stored.containsKey(fpI) && !stored.containsKey(fpJ);
    }

    private static SkipReason skipReason(RevolutRow row, PlanContext ctx) {
        String state = row.state();
        if ("PENDING".equalsIgnoreCase(state)) return SkipReason.PENDING;
        if (!"COMPLETED".equalsIgnoreCase(state)) return SkipReason.REVERTED; // REVERTED, DECLINED, anything else
        if (!row.currency().equalsIgnoreCase(ctx.accountCurrency())) return SkipReason.OTHER_CURRENCY;
        if ("EXCHANGE".equalsIgnoreCase(row.type())) return SkipReason.EXCHANGE;
        if (account(row, ctx) == null) return SkipReason.PRODUCT_IGNORED;
        if (row.amountCents() == 0) return SkipReason.ZERO_AMOUNT;
        return null;
    }

    private static UUID account(RevolutRow row, PlanContext ctx) {
        return ctx.productMapping().get(row.product());
    }

    private static LocalDate dateOf(RevolutRow row) {
        return (row.completedAt() != null ? row.completedAt() : row.startedAt()).toLocalDate();
    }

    private static void addFee(List<PlannedRow> out, String parentFingerprint, RevolutRow row, PlanContext ctx) {
        if (row.feeCents() <= 0) return;
        out.add(build(RowFingerprints.fee(parentFingerprint), row, TransactionKind.EXPENSE, row.feeCents(),
                account(row, ctx), null, ctx.feeDescription(), ctx));
    }

    private static PlannedRow build(String rowId, RevolutRow row, TransactionKind kind, long amount, UUID account,
            UUID destination, String fullDescription, PlanContext ctx) {
        LocalDate date = dateOf(row);
        String key = DescriptionKeys.of(fullDescription);
        String description = fullDescription.length() > MAX_DESCRIPTION
                ? fullDescription.substring(0, MAX_DESCRIPTION)
                : fullDescription;
        UUID category = null;
        if (kind != TransactionKind.TRANSFER) {
            category = ctx.memory().get(new MemoryKey(key, kind));
            if (category == null) category = ctx.defaultCategories().get(kind);
        }
        RowStatus status = RowStatus.NEW;
        if (ctx.existingFingerprints().containsKey(rowId)) {
            status = RowStatus.ALREADY_IMPORTED;
        } else if (manualInWindow(row, kind, amount, account, ctx)) {
            status = RowStatus.POSSIBLE_DUPLICATE;
        }
        return new PlannedRow(rowId, date, kind, amount, description, key, account, destination, category, status);
    }

    /**
     * A manual transaction typed while the payment was pending usually carries the purchase day, while the import
     * stores the completion day; any day from the one to the other counts.
     */
    private static boolean manualInWindow(
            RevolutRow row, TransactionKind kind, long amount, UUID account, PlanContext ctx) {
        LocalDate last = dateOf(row);
        for (LocalDate d = row.startedAt().toLocalDate(); !d.isAfter(last); d = d.plusDays(1)) {
            if (ctx.manualTransactions().contains(new DuplicateKey(d, kind, amount, account))) return true;
        }
        return false;
    }
}
