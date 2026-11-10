package br.dev.duofinance.imports.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.imports.service.PlanContext.DuplicateKey;
import br.dev.duofinance.imports.service.PlanContext.MemoryKey;
import br.dev.duofinance.transaction.model.TransactionKind;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ImportPlannerTest {

    private static final UUID CURRENT = UUID.randomUUID();
    private static final UUID SAVINGS = UUID.randomUUID();
    private static final UUID CAT_EXPENSE = UUID.randomUUID();
    private static final UUID CAT_INCOME = UUID.randomUUID();

    private static Map<String, UUID> mapping() {
        Map<String, UUID> m = new HashMap<>();
        m.put("Current", CURRENT);
        m.put("Savings", SAVINGS);
        m.put("Ignored", null);
        return m;
    }

    private static PlanContext ctx() {
        return ctx(Set.of(), Map.of(), Set.of());
    }

    private static PlanContext ctx(Set<String> known, Map<MemoryKey, UUID> memory, Set<DuplicateKey> manual) {
        return new PlanContext("BRL", mapping(), known, memory,
                Map.of(TransactionKind.EXPENSE, CAT_EXPENSE, TransactionKind.INCOME, CAT_INCOME), manual,
                "Revolut fee");
    }

    private static RevolutRow row(String product, String started, String completed, String desc, String amount,
            long cents, long fee, String currency, String state, String type) {
        return new RevolutRow(2, type, product, LocalDateTime.parse(started.replace(' ', 'T')), started,
                completed == null ? null : LocalDateTime.parse(completed.replace(' ', 'T')), desc, cents, amount, fee,
                currency, state);
    }

    private static RevolutRow card(long cents) {
        return row("Current", "2026-01-10 10:00:00", "2026-01-11 12:00:00", "Shop", String.valueOf(cents / 100.0),
                cents, 0, "BRL", "COMPLETED", "CARD_PAYMENT");
    }

    private static RevolutRow with(String product, String started, String desc, long cents) {
        return row(product, started, "2026-01-11 12:00:00", desc, cents + "", cents, 0, "BRL", "COMPLETED",
                "TRANSFER");
    }

    @Test
    void plan_completedNegative_isExpenseWithAbsoluteAmount() {
        PlannedRow p = ImportPlanner.plan(List.of(card(-1250)), ctx()).rows().get(0);
        assertThat(p.kind()).isEqualTo(TransactionKind.EXPENSE);
        assertThat(p.amountCents()).isEqualTo(1250);
        assertThat(p.bankAccountId()).isEqualTo(CURRENT);
        assertThat(p.status()).isEqualTo(RowStatus.NEW);
    }

    @Test
    void plan_completedPositive_isIncome() {
        assertThat(ImportPlanner.plan(List.of(card(900)), ctx()).rows().get(0).kind())
                .isEqualTo(TransactionKind.INCOME);
    }

    @Test
    void plan_pending_skippedPending() {
        RevolutRow r = row("Current", "2026-01-10 10:00:00", null, "Shop", "-1.00", -100, 0, "BRL", "PENDING",
                "CARD_PAYMENT");
        ImportPlan plan = ImportPlanner.plan(List.of(r), ctx());
        assertThat(plan.rows()).isEmpty();
        assertThat(plan.skipped()).singleElement().satisfies(s -> {
            assertThat(s.reason()).isEqualTo(SkipReason.PENDING);
            assertThat(s.date()).isEqualTo(LocalDate.of(2026, 1, 10));
        });
    }

    @Test
    void plan_revertedAndDeclined_skippedReverted() {
        RevolutRow a = row("Current", "2026-01-10 10:00:00", "2026-01-11 12:00:00", "A", "-1.00", -100, 0, "BRL",
                "REVERTED", "CARD_PAYMENT");
        RevolutRow b = row("Current", "2026-01-10 10:00:00", "2026-01-11 12:00:00", "B", "-1.00", -100, 0, "BRL",
                "DECLINED", "CARD_PAYMENT");
        ImportPlan plan = ImportPlanner.plan(List.of(a, b), ctx());
        assertThat(plan.skipped()).extracting(SkippedRow::reason).containsExactly(SkipReason.REVERTED,
                SkipReason.REVERTED);
    }

    @Test
    void plan_otherCurrency_skipped() {
        RevolutRow r = row("Current", "2026-01-10 10:00:00", "2026-01-11 12:00:00", "A", "-1.00", -100, 0, "EUR",
                "COMPLETED", "CARD_PAYMENT");
        assertThat(ImportPlanner.plan(List.of(r), ctx()).skipped()).extracting(SkippedRow::reason)
                .containsExactly(SkipReason.OTHER_CURRENCY);
    }

    @Test
    void plan_exchange_skipped() {
        RevolutRow r = row("Current", "2026-01-10 10:00:00", "2026-01-11 12:00:00", "A", "-1.00", -100, 0, "BRL",
                "COMPLETED", "EXCHANGE");
        assertThat(ImportPlanner.plan(List.of(r), ctx()).skipped()).extracting(SkippedRow::reason)
                .containsExactly(SkipReason.EXCHANGE);
    }

    @Test
    void plan_zeroAmount_skipped() {
        assertThat(ImportPlanner.plan(List.of(card(0)), ctx()).skipped()).extracting(SkippedRow::reason)
                .containsExactly(SkipReason.ZERO_AMOUNT);
    }

    @Test
    void plan_unmappedOrIgnoredProduct_skippedProductIgnored() {
        RevolutRow ignored = with("Ignored", "2026-01-10 10:00:00", "A", -100);
        RevolutRow unmapped = with("Other", "2026-01-10 10:00:00", "B", -100);
        ImportPlan plan = ImportPlanner.plan(List.of(ignored, unmapped), ctx());
        assertThat(plan.rows()).isEmpty();
        assertThat(plan.skipped()).extracting(SkippedRow::reason).containsExactly(SkipReason.PRODUCT_IGNORED,
                SkipReason.PRODUCT_IGNORED);
    }

    @Test
    void plan_feeCreatesSeparateExpense() {
        RevolutRow r = row("Current", "2026-01-10 10:00:00", "2026-01-11 12:00:00", "Shop", "-10.00", -1000, 50,
                "BRL", "COMPLETED", "CARD_PAYMENT");
        List<PlannedRow> rows = ImportPlanner.plan(List.of(r), ctx()).rows();
        assertThat(rows).hasSize(2);
        PlannedRow fee = rows.get(1);
        assertThat(fee.kind()).isEqualTo(TransactionKind.EXPENSE);
        assertThat(fee.amountCents()).isEqualTo(50);
        assertThat(fee.description()).isEqualTo("Revolut fee");
        assertThat(fee.rowId()).isEqualTo(RowFingerprints.fee(rows.get(0).rowId()));
        assertThat(fee.date()).isEqualTo(rows.get(0).date());
        assertThat(fee.bankAccountId()).isEqualTo(CURRENT);
    }

    @Test
    void plan_oppositeAmountsSameStartedAcrossMappedProducts_becomeOneTransfer() {
        RevolutRow out = with("Current", "2026-01-10 10:00:00", "To savings", -5000);
        RevolutRow in = with("Savings", "2026-01-10 10:00:00", "From current", 5000);
        ImportPlan plan = ImportPlanner.plan(List.of(in, out), ctx());
        assertThat(plan.rows()).hasSize(1);
        PlannedRow t = plan.rows().get(0);
        assertThat(t.kind()).isEqualTo(TransactionKind.TRANSFER);
        assertThat(t.bankAccountId()).isEqualTo(CURRENT);
        assertThat(t.destinationBankAccountId()).isEqualTo(SAVINGS);
        assertThat(t.amountCents()).isEqualTo(5000);
        assertThat(t.suggestedCategoryId()).isNull();
        assertThat(t.descriptionKey()).isEqualTo(DescriptionKeys.of("To savings"));
        assertThat(t.rowId()).isEqualTo(RowFingerprints.of(List.of(in, out)).get(1));
    }

    @Test
    void plan_transferWithoutPair_staysExpense() {
        RevolutRow out = with("Current", "2026-01-10 10:00:00", "To savings", -5000);
        RevolutRow other = with("Savings", "2026-01-10 10:00:01", "From current", 5000);
        assertThat(ImportPlanner.plan(List.of(out, other), ctx()).rows()).extracting(PlannedRow::kind)
                .containsExactly(TransactionKind.EXPENSE, TransactionKind.INCOME);
    }

    @Test
    void plan_oppositeAmountsSameProduct_notPaired() {
        RevolutRow out = with("Current", "2026-01-10 10:00:00", "A", -5000);
        RevolutRow in = with("Current", "2026-01-10 10:00:00", "B", 5000);
        assertThat(ImportPlanner.plan(List.of(out, in), ctx()).rows()).extracting(PlannedRow::kind)
                .containsExactly(TransactionKind.EXPENSE, TransactionKind.INCOME);
    }

    @Test
    void plan_knownFingerprint_isAlreadyImported() {
        RevolutRow r = card(-100);
        String fp = RowFingerprints.of(List.of(r)).get(0);
        assertThat(ImportPlanner.plan(List.of(r), ctx(Set.of(fp), Map.of(), Set.of())).rows().get(0).status())
                .isEqualTo(RowStatus.ALREADY_IMPORTED);
    }

    @Test
    void plan_matchingManualTransaction_isPossibleDuplicate() {
        Set<DuplicateKey> manual = new HashSet<>();
        manual.add(new DuplicateKey(LocalDate.of(2026, 1, 11), TransactionKind.EXPENSE, 100, CURRENT));
        assertThat(ImportPlanner.plan(List.of(card(-100)), ctx(Set.of(), Map.of(), manual)).rows().get(0).status())
                .isEqualTo(RowStatus.POSSIBLE_DUPLICATE);
    }

    @Test
    void plan_suggestsMemoryThenDefaultThenNull() {
        UUID remembered = UUID.randomUUID();
        Map<MemoryKey, UUID> memory = Map.of(new MemoryKey(DescriptionKeys.of("Shop"), TransactionKind.EXPENSE),
                remembered);
        assertThat(ImportPlanner.plan(List.of(card(-100)), ctx(Set.of(), memory, Set.of())).rows().get(0)
                .suggestedCategoryId()).isEqualTo(remembered);
        assertThat(ImportPlanner.plan(List.of(card(-100)), ctx()).rows().get(0).suggestedCategoryId())
                .isEqualTo(CAT_EXPENSE);
        PlanContext bare = new PlanContext("BRL", mapping(), Set.of(), Map.of(), Map.of(), Set.of(), "Revolut fee");
        assertThat(ImportPlanner.plan(List.of(card(-100)), bare).rows().get(0).suggestedCategoryId()).isNull();
    }

    @Test
    void plan_usesCompletedDateDayWithoutTimeZoneShift() {
        RevolutRow r = row("Current", "2026-01-10 23:59:59", "2026-01-11 00:00:01", "Shop", "-1.00", -100, 0, "BRL",
                "COMPLETED", "CARD_PAYMENT");
        assertThat(ImportPlanner.plan(List.of(r), ctx()).rows().get(0).date()).isEqualTo(LocalDate.of(2026, 1, 11));
    }

    @Test
    void plan_cutsDescriptionTo140() {
        String longDesc = "x".repeat(200);
        RevolutRow r = row("Current", "2026-01-10 10:00:00", "2026-01-11 12:00:00", longDesc, "-1.00", -100, 0,
                "BRL", "COMPLETED", "CARD_PAYMENT");
        PlannedRow p = ImportPlanner.plan(List.of(r), ctx()).rows().get(0);
        assertThat(p.description()).hasSize(140);
        assertThat(p.rowId()).isEqualTo(RowFingerprints.of(List.of(r)).get(0));
    }

    @Test
    void plan_listsDistinctProductsInFileOrder() {
        RevolutRow a = with("Savings", "2026-01-10 10:00:00", "A", -100);
        RevolutRow b = with("Current", "2026-01-10 10:00:01", "B", -100);
        RevolutRow c = with("Savings", "2026-01-10 10:00:02", "C", -100);
        assertThat(ImportPlanner.plan(List.of(a, b, c), ctx()).products()).containsExactly("Savings", "Current");
    }
}
