package br.dev.duofinance.imports.service;

import br.dev.duofinance.goal.service.PocketNames;
import br.dev.duofinance.imports.dto.PocketInfo;
import br.dev.duofinance.imports.service.PlanContext.DuplicateKey;
import br.dev.duofinance.imports.service.PlanContext.GoalRef;
import br.dev.duofinance.imports.service.PlanContext.MemoryKey;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.jspecify.annotations.Nullable;

/** Pure rule engine: parsed Revolut rows plus a context in, planned transactions and skipped rows out. */
public final class ImportPlanner {

    private static final int MAX_DESCRIPTION = 140;
    private static final String CURRENT = "Current";

    private ImportPlanner() {}

    public static ImportPlan plan(List<RevolutRow> rows, PlanContext ctx) {
        List<String> fingerprints = RowFingerprints.of(rows);
        LinkedHashSet<String> products = new LinkedHashSet<>();
        for (RevolutRow row : rows) products.add(row.product());

        // The statement's own account: savings-product rows (pocket moves, interest) belong to it too.
        UUID current = ctx.productMapping().get(CURRENT);
        boolean[] pocket = new boolean[rows.size()];
        UUID[] accounts = new UUID[rows.size()];
        List<SkippedRow> skipped = new ArrayList<>();
        List<Integer> importable = new ArrayList<>();

        for (int i = 0; i < rows.size(); i++) {
            RevolutRow row = rows.get(i);
            pocket[i] = PocketRows.isPocketRow(row);
            accounts[i] =
                    PocketRows.isSavingsProduct(row.product()) ? current : ctx.productMapping().get(row.product());
            SkipReason reason = skipReason(row, accounts[i] == null ? SkipReason.PRODUCT_IGNORED : null, ctx);
            if (reason == null) {
                importable.add(i);
            } else {
                skipped.add(new SkippedRow(
                        row.lineNumber(), dateOf(row), row.description(), row.amountRaw(), row.currency(), reason));
            }
        }

        int[] partner = pairTransfers(rows, importable, fingerprints, accounts, pocket, ctx);

        List<PlannedRow> planned = new ArrayList<>();
        Set<String> pocketMoves = new HashSet<>(); // row ids of planned pocket moves: each move is planned once
        for (int i : importable) {
            int p = partner[i];
            if (p != -1 && p < i) continue; // already emitted together with its partner
            RevolutRow row = rows.get(i);
            if (p == -1 && pocket[i]) {
                planPocketLeg(planned, pocketMoves, fingerprints.get(i), row, current, ctx);
            } else if (p == -1) {
                TransactionKind kind = row.amountCents() < 0 ? TransactionKind.EXPENSE : TransactionKind.INCOME;
                planned.add(build(fingerprints.get(i), row, kind, Math.abs(row.amountCents()), accounts[i], null,
                        row.description(), ctx, null));
                addFee(planned, fingerprints.get(i), row, accounts[i], ctx);
            } else {
                int neg = row.amountCents() < 0 ? i : p;
                int pos = neg == i ? p : i;
                RevolutRow negRow = rows.get(neg);
                RevolutRow posRow = rows.get(pos);
                PocketInfo info = null;
                if (pocket[neg] || pocket[pos]) {
                    if (!pocketMoves.add(fingerprints.get(neg))) continue;
                    info = pocketInfo(pocket[pos] ? posRow : negRow, ctx);
                }
                // A pocket move stays in the statement's account: no destination account.
                planned.add(build(fingerprints.get(neg), negRow, TransactionKind.TRANSFER,
                        Math.abs(negRow.amountCents()), accounts[neg], info == null ? accounts[pos] : null,
                        negRow.description(), ctx, info));
                addFee(planned, fingerprints.get(neg), negRow, accounts[neg], ctx);
                addFee(planned, fingerprints.get(pos), posRow, accounts[pos], ctx);
            }
        }
        return new ImportPlan(List.copyOf(products), planned, skipped);
    }

    /**
     * A pocket leg without its partner is still one pocket move in the statement's account. It takes the
     * fingerprint of the move's negative leg, synthesised when this leg is the positive one, so the move is recognised
     * as already imported whichever legs a later statement carries.
     */
    private static void planPocketLeg(List<PlannedRow> out, Set<String> pocketMoves, String fingerprint,
            RevolutRow row, UUID current, PlanContext ctx) {
        String rowId = fingerprint;
        if (row.amountCents() > 0) {
            String otherProduct = PocketRows.isSavingsProduct(row.product())
                    ? CURRENT
                    : PocketRows.savingsProductFor(row.description());
            rowId = RowFingerprints.forLeg(row, otherProduct, negate(row.amountRaw()));
        }
        if (!pocketMoves.add(rowId)) return;
        out.add(build(rowId, row, TransactionKind.TRANSFER, Math.abs(row.amountCents()), current, null,
                row.description(), ctx, pocketInfo(row, ctx)));
        addFee(out, fingerprint, row, current, ctx);
    }

    private static PocketInfo pocketInfo(RevolutRow row, PlanContext ctx) {
        boolean savingsLeg = PocketRows.isSavingsProduct(row.product());
        PocketInfo.Direction direction =
                savingsLeg == row.amountCents() > 0 ? PocketInfo.Direction.INTO : PocketInfo.Direction.OUT_OF;
        String name = PocketRows.pocketName(row.description()).orElse(null);
        if (name == null) return new PocketInfo(null, direction, null, PocketInfo.Match.NONE);
        String key = PocketNames.normalise(name);
        GoalRef byName = null;
        int sameName = 0;
        for (GoalRef goal : ctx.activeGoals()) {
            String linked = PocketNames.clean(goal.revolutPocket());
            if (linked != null && PocketNames.normalise(linked).equals(key)) {
                return new PocketInfo(name, direction, goal.id(), PocketInfo.Match.LINKED);
            }
            if (linked == null && PocketNames.normalise(goal.name()).equals(key)) {
                byName = goal;
                sameName++;
            }
        }
        if (sameName == 1) return new PocketInfo(name, direction, byName.id(), PocketInfo.Match.NAME_MATCH);
        return new PocketInfo(name, direction, null, PocketInfo.Match.NONE);
    }

    private static String negate(String amountRaw) {
        if (amountRaw.startsWith("-")) return amountRaw.substring(1);
        if (amountRaw.startsWith("+")) return "-" + amountRaw.substring(1);
        return "-" + amountRaw;
    }

    /**
     * Pairs opposite-sign rows with the same Started Date on different mapped accounts, first match in file order. The
     * two legs of a pocket move pair on the statement's own account, and a pocket leg never pairs with another row. A
     * pair whose negative leg is stored as a TRANSFER is the already imported transfer; a pair with either leg stored
     * otherwise (INCOME/EXPENSE: the mapping changed between imports) is not paired, so each leg keeps its own status.
     */
    private static int[] pairTransfers(List<RevolutRow> rows, List<Integer> importable, List<String> fingerprints,
            UUID[] accounts, boolean[] pocket, PlanContext ctx) {
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
                        && pocket[i] == pocket[j]
                        && (pocket[i] ? !ri.product().equals(rj.product()) : !accounts[i].equals(accounts[j]))
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

    private static @Nullable SkipReason skipReason(
            RevolutRow row, @Nullable SkipReason accountProblem, PlanContext ctx) {
        String state = row.state();
        if ("PENDING".equalsIgnoreCase(state)) return SkipReason.PENDING;
        if (!"COMPLETED".equalsIgnoreCase(state)) return SkipReason.REVERTED; // REVERTED, DECLINED, anything else
        if (!row.currency().equalsIgnoreCase(ctx.accountCurrency())) return SkipReason.OTHER_CURRENCY;
        if ("EXCHANGE".equalsIgnoreCase(row.type())) return SkipReason.EXCHANGE;
        if (accountProblem != null) return accountProblem;
        if (row.amountCents() == 0) return SkipReason.ZERO_AMOUNT;
        return null;
    }

    private static LocalDate dateOf(RevolutRow row) {
        return (row.completedAt() != null ? row.completedAt() : row.startedAt()).toLocalDate();
    }

    private static void addFee(
            List<PlannedRow> out, String parentFingerprint, RevolutRow row, UUID account, PlanContext ctx) {
        if (row.feeCents() <= 0) return;
        out.add(build(RowFingerprints.fee(parentFingerprint), row, TransactionKind.EXPENSE, row.feeCents(), account,
                null, ctx.feeDescription(), ctx, null));
    }

    private static PlannedRow build(String rowId, RevolutRow row, TransactionKind kind, long amount, UUID account,
            UUID destination, String fullDescription, PlanContext ctx, @Nullable PocketInfo pocket) {
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
        } else if (pocket == null && manualInWindow(row, kind, amount, account, ctx)) {
            status = RowStatus.POSSIBLE_DUPLICATE;
        }
        return new PlannedRow(
                rowId, date, kind, amount, description, key, account, destination, category, status, pocket,
                row.startedAt(), row.lineNumber());
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
