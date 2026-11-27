package br.dev.duofinance.imports.service;

import static org.assertj.core.api.Assertions.assertThat;

import br.dev.duofinance.imports.dto.PocketInfo;
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
        m.put("Vault", SAVINGS);
        m.put("Ignored", null);
        return m;
    }

    private static PlanContext ctx() {
        return ctx(Map.of(), Map.of(), Set.of());
    }

    private static PlanContext ctx(Map<String, TransactionKind> known, Map<MemoryKey, UUID> memory, Set<DuplicateKey> manual) {
        return new PlanContext("BRL", mapping(), known, memory,
                Map.of(TransactionKind.EXPENSE, CAT_EXPENSE, TransactionKind.INCOME, CAT_INCOME), manual,
                "Revolut fee", null, List.of());
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
        RevolutRow in = with("Vault", "2026-01-10 10:00:00", "From current", 5000);
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
        RevolutRow other = with("Vault", "2026-01-10 10:00:01", "From current", 5000);
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
        assertThat(ImportPlanner.plan(List.of(r), ctx(Map.of(fp, TransactionKind.EXPENSE), Map.of(), Set.of())).rows().get(0).status())
                .isEqualTo(RowStatus.ALREADY_IMPORTED);
    }

    @Test
    void plan_matchingManualTransaction_isPossibleDuplicate() {
        Set<DuplicateKey> manual = new HashSet<>();
        manual.add(new DuplicateKey(LocalDate.of(2026, 1, 11), TransactionKind.EXPENSE, 100, CURRENT));
        assertThat(ImportPlanner.plan(List.of(card(-100)), ctx(Map.of(), Map.of(), manual)).rows().get(0).status())
                .isEqualTo(RowStatus.POSSIBLE_DUPLICATE);
    }

    @Test
    void plan_manualOnPurchaseDayBeforeCompletion_isPossibleDuplicate() {
        Set<DuplicateKey> manual =
                Set.of(new DuplicateKey(LocalDate.of(2026, 1, 10), TransactionKind.EXPENSE, 100, CURRENT));
        assertThat(ImportPlanner.plan(List.of(card(-100)), ctx(Map.of(), Map.of(), manual)).rows().get(0).status())
                .isEqualTo(RowStatus.POSSIBLE_DUPLICATE);
    }

    @Test
    void plan_manualBeforePurchaseDay_isNew() {
        Set<DuplicateKey> manual =
                Set.of(new DuplicateKey(LocalDate.of(2026, 1, 9), TransactionKind.EXPENSE, 100, CURRENT));
        assertThat(ImportPlanner.plan(List.of(card(-100)), ctx(Map.of(), Map.of(), manual)).rows().get(0).status())
                .isEqualTo(RowStatus.NEW);
    }

    @Test
    void plan_manualAfterCompletionDay_isNew() {
        Set<DuplicateKey> manual =
                Set.of(new DuplicateKey(LocalDate.of(2026, 1, 12), TransactionKind.EXPENSE, 100, CURRENT));
        assertThat(ImportPlanner.plan(List.of(card(-100)), ctx(Map.of(), Map.of(), manual)).rows().get(0).status())
                .isEqualTo(RowStatus.NEW);
    }

    @Test
    void plan_suggestsMemoryThenDefaultThenNull() {
        UUID remembered = UUID.randomUUID();
        Map<MemoryKey, UUID> memory = Map.of(new MemoryKey(DescriptionKeys.of("Shop"), TransactionKind.EXPENSE),
                remembered);
        assertThat(ImportPlanner.plan(List.of(card(-100)), ctx(Map.of(), memory, Set.of())).rows().get(0)
                .suggestedCategoryId()).isEqualTo(remembered);
        assertThat(ImportPlanner.plan(List.of(card(-100)), ctx()).rows().get(0).suggestedCategoryId())
                .isEqualTo(CAT_EXPENSE);
        PlanContext bare = new PlanContext("BRL", mapping(), Map.of(), Map.of(), Map.of(), Set.of(), "Revolut fee", null, List.of());
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
        RevolutRow a = with("Vault", "2026-01-10 10:00:00", "A", -100);
        RevolutRow b = with("Current", "2026-01-10 10:00:01", "B", -100);
        RevolutRow c = with("Vault", "2026-01-10 10:00:02", "C", -100);
        assertThat(ImportPlanner.plan(List.of(a, b, c), ctx()).products()).containsExactly("Vault", "Current");
    }

    private void assertLegsSeparate(boolean positiveKnown) {
        RevolutRow out = with("Current", "2026-01-10 10:00:00", "To savings", -5000);
        RevolutRow in = with("Vault", "2026-01-10 10:00:00", "From current", 5000);
        List<String> fps = RowFingerprints.of(List.of(out, in));
        String known = positiveKnown ? fps.get(1) : fps.get(0);
        List<PlannedRow> rows =
                ImportPlanner.plan(List.of(out, in), ctx(Map.of(known, positiveKnown ? TransactionKind.INCOME : TransactionKind.EXPENSE), Map.of(),
                        Set.of())).rows();
        assertThat(rows).extracting(PlannedRow::kind).containsExactly(TransactionKind.EXPENSE, TransactionKind.INCOME);
        assertThat(rows).extracting(PlannedRow::bankAccountId).containsExactly(CURRENT, SAVINGS);
        assertThat(rows).extracting(PlannedRow::rowId).containsExactly(fps.get(0), fps.get(1));
        assertThat(rows).extracting(PlannedRow::status).containsExactly(
                positiveKnown ? RowStatus.NEW : RowStatus.ALREADY_IMPORTED,
                positiveKnown ? RowStatus.ALREADY_IMPORTED : RowStatus.NEW);
    }

    @Test
    void plan_transferWithPositiveLegAlreadyImported_plansLegsSeparately() {
        assertLegsSeparate(true);
    }

    @Test
    void plan_transferWithNegativeLegStoredAsExpense_plansLegsSeparately() {
        assertLegsSeparate(false);
    }

    @Test
    void plan_transferWithNegativeLegStoredAsTransfer_isOneAlreadyImportedTransfer() {
        RevolutRow out = with("Current", "2026-01-10 10:00:00", "To savings", -5000);
        RevolutRow in = with("Vault", "2026-01-10 10:00:00", "From current", 5000);
        List<String> fps = RowFingerprints.of(List.of(in, out));
        List<PlannedRow> rows = ImportPlanner.plan(
                        List.of(in, out), ctx(Map.of(fps.get(1), TransactionKind.TRANSFER), Map.of(), Set.of()))
                .rows();
        assertThat(rows).singleElement().satisfies(t -> {
            assertThat(t.kind()).isEqualTo(TransactionKind.TRANSFER);
            assertThat(t.rowId()).isEqualTo(fps.get(1));
            assertThat(t.bankAccountId()).isEqualTo(CURRENT);
            assertThat(t.destinationBankAccountId()).isEqualTo(SAVINGS);
            assertThat(t.status()).isEqualTo(RowStatus.ALREADY_IMPORTED);
        });
    }

    @Test
    void plan_alreadyImportedWinsOverPossibleDuplicate() {
        RevolutRow r = card(-100);
        String fp = RowFingerprints.of(List.of(r)).get(0);
        Set<DuplicateKey> manual =
                Set.of(new DuplicateKey(LocalDate.of(2026, 1, 11), TransactionKind.EXPENSE, 100, CURRENT));
        assertThat(ImportPlanner.plan(List.of(r), ctx(Map.of(fp, TransactionKind.EXPENSE), Map.of(), manual)).rows().get(0).status())
                .isEqualTo(RowStatus.ALREADY_IMPORTED);
    }

    @Test
    void plan_pairedTransferFees_createExpensePerSide() {
        RevolutRow out = row("Current", "2026-01-10 10:00:00", "2026-01-11 12:00:00", "Out", "-50.00", -5000, 30,
                "BRL", "COMPLETED", "TRANSFER");
        RevolutRow in = row("Vault", "2026-01-10 10:00:00", "2026-01-11 12:00:00", "In", "50.00", 5000, 20, "BRL",
                "COMPLETED", "TRANSFER");
        List<String> fps = RowFingerprints.of(List.of(out, in));
        List<PlannedRow> rows = ImportPlanner.plan(List.of(out, in), ctx()).rows();
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).kind()).isEqualTo(TransactionKind.TRANSFER);
        assertThat(rows.get(1).kind()).isEqualTo(TransactionKind.EXPENSE);
        assertThat(rows.get(1).amountCents()).isEqualTo(30);
        assertThat(rows.get(1).bankAccountId()).isEqualTo(CURRENT);
        assertThat(rows.get(1).rowId()).isEqualTo(RowFingerprints.fee(fps.get(0)));
        assertThat(rows.get(2).kind()).isEqualTo(TransactionKind.EXPENSE);
        assertThat(rows.get(2).amountCents()).isEqualTo(20);
        assertThat(rows.get(2).bankAccountId()).isEqualTo(SAVINGS);
        assertThat(rows.get(2).rowId()).isEqualTo(RowFingerprints.fee(fps.get(1)));
    }

    @Test
    void plan_zeroAmountWithFee_producesNoFeeRow() {
        RevolutRow r = row("Current", "2026-01-10 10:00:00", "2026-01-11 12:00:00", "Z", "0.00", 0, 50, "BRL",
                "COMPLETED", "CARD_PAYMENT");
        ImportPlan plan = ImportPlanner.plan(List.of(r), ctx());
        assertThat(plan.rows()).isEmpty();
        assertThat(plan.skipped()).extracting(SkippedRow::reason).containsExactly(SkipReason.ZERO_AMOUNT);
    }

    @Test
    void plan_transferCounterpartSkipped_staysExpense() {
        RevolutRow out = with("Current", "2026-01-10 10:00:00", "To savings", -5000);
        RevolutRow pending = row("Vault", "2026-01-10 10:00:00", null, "From current", "50.00", 5000, 0, "BRL",
                "PENDING", "TRANSFER");
        ImportPlan plan = ImportPlanner.plan(List.of(out, pending), ctx());
        assertThat(plan.rows()).singleElement().satisfies(p -> {
            assertThat(p.kind()).isEqualTo(TransactionKind.EXPENSE);
            assertThat(p.bankAccountId()).isEqualTo(CURRENT);
        });
        assertThat(plan.skipped()).extracting(SkippedRow::reason).containsExactly(SkipReason.PENDING);
    }

    @Test
    void plan_threeWayTie_pairsFirstMatchInFileOrder() {
        RevolutRow out = with("Current", "2026-01-10 10:00:00", "Out", -5000);
        RevolutRow in1 = with("Vault", "2026-01-10 10:00:00", "In one", 5000);
        RevolutRow in2 = with("Vault", "2026-01-10 10:00:00", "In two", 5000);
        List<String> fps = RowFingerprints.of(List.of(out, in1, in2));
        List<PlannedRow> rows = ImportPlanner.plan(List.of(out, in1, in2), ctx()).rows();
        assertThat(rows).extracting(PlannedRow::kind).containsExactly(TransactionKind.TRANSFER,
                TransactionKind.INCOME);
        assertThat(rows.get(0).rowId()).isEqualTo(fps.get(0));
        assertThat(rows.get(1).rowId()).isEqualTo(fps.get(2));
        assertThat(rows.get(1).description()).isEqualTo("In two");
    }

    @Test
    void plan_feeRowSuggestion_usesMemoryThenDefaultExpense() {
        RevolutRow r = row("Current", "2026-01-10 10:00:00", "2026-01-11 12:00:00", "Shop", "-10.00", -1000, 50,
                "BRL", "COMPLETED", "CARD_PAYMENT");
        assertThat(ImportPlanner.plan(List.of(r), ctx()).rows().get(1).suggestedCategoryId()).isEqualTo(CAT_EXPENSE);
        UUID feeCat = UUID.randomUUID();
        Map<MemoryKey, UUID> memory =
                Map.of(new MemoryKey(DescriptionKeys.of("Revolut fee"), TransactionKind.EXPENSE), feeCat);
        List<PlannedRow> rows = ImportPlanner.plan(List.of(r), ctx(Map.of(), memory, Set.of())).rows();
        assertThat(rows.get(1).suggestedCategoryId()).isEqualTo(feeCat);
        assertThat(rows.get(0).suggestedCategoryId()).isEqualTo(CAT_EXPENSE);
    }

    // --- Revolut pockets (anonymised pt-PT sample) ---

    private static final UUID POCKETS = UUID.randomUUID();
    private static final String RESERVA = "Carregamento de subconta EUR Reserva de Emergência de EUR";
    private static final String SETUP = "Carregamento de subconta EUR Setup de EUR";

    private static Map<String, UUID> pocketMapping() {
        return Map.of("Current", CURRENT, "Poupanças", POCKETS);
    }

    private static PlanContext pocketCtx(Map<String, UUID> mapping, Map<String, TransactionKind> known,
            UUID pocketsBankAccountId, List<PlanContext.GoalRef> goals) {
        return new PlanContext("EUR", mapping, known, Map.of(),
                Map.of(TransactionKind.EXPENSE, CAT_EXPENSE, TransactionKind.INCOME, CAT_INCOME), Set.of(),
                "Revolut fee", pocketsBankAccountId, goals);
    }

    private static PlanContext pocketCtx() {
        return pocketCtx(pocketMapping(), Map.of(), null, List.of());
    }

    private static RevolutRow leg(String product, String started, String desc, String amount) {
        long cents = Math.round(Double.parseDouble(amount) * 100);
        return row(product, started, started, desc, amount, cents, 0, "EUR", "COMPLETED", "Transferência");
    }

    @Test
    void pocketDeposit_bothLegs_oneTransferIntoPockets() {
        RevolutRow in = leg("Poupanças", "2026-11-26 14:34:39", RESERVA, "250.00");
        RevolutRow out = leg("Current", "2026-11-26 14:34:39", RESERVA, "-250.00");
        ImportPlan plan = ImportPlanner.plan(List.of(in, out), pocketCtx());
        assertThat(plan.skipped()).isEmpty();
        assertThat(plan.rows()).singleElement().satisfies(t -> {
            assertThat(t.kind()).isEqualTo(TransactionKind.TRANSFER);
            assertThat(t.bankAccountId()).isEqualTo(CURRENT);
            assertThat(t.destinationBankAccountId()).isEqualTo(POCKETS);
            assertThat(t.amountCents()).isEqualTo(25000);
            assertThat(t.rowId()).isEqualTo(RowFingerprints.of(List.of(in, out)).get(1));
            assertThat(t.pocket().direction()).isEqualTo(PocketInfo.Direction.INTO);
            assertThat(t.pocket().name()).isEqualTo("Reserva de Emergência");
        });
    }

    @Test
    void pocketDeposit_onlySavingsLeg_transferWithCurrentLegFingerprint() {
        RevolutRow in = leg("Poupanças", "2026-11-26 14:55:30", RESERVA, "1000.00");
        RevolutRow currentLeg = leg("Current", "2026-11-26 14:55:30", RESERVA, "-1000.00");
        ImportPlan plan = ImportPlanner.plan(List.of(in), pocketCtx());
        assertThat(plan.rows()).singleElement().satisfies(t -> {
            assertThat(t.kind()).isEqualTo(TransactionKind.TRANSFER);
            assertThat(t.bankAccountId()).isEqualTo(CURRENT);
            assertThat(t.destinationBankAccountId()).isEqualTo(POCKETS);
            assertThat(t.amountCents()).isEqualTo(100000);
            assertThat(t.rowId()).isEqualTo(RowFingerprints.of(List.of(currentLeg)).get(0));
            assertThat(t.status()).isEqualTo(RowStatus.NEW);
            assertThat(t.pocket().direction()).isEqualTo(PocketInfo.Direction.INTO);
        });
    }

    @Test
    void pocketDeposit_partnerArrivesLater_alreadyImported() {
        RevolutRow in = leg("Poupanças", "2026-11-26 14:55:30", RESERVA, "1000.00");
        RevolutRow currentLeg = leg("Current", "2026-11-26 14:55:30", RESERVA, "-1000.00");
        String stored = RowFingerprints.of(List.of(currentLeg)).get(0);
        PlanContext ctx = pocketCtx(pocketMapping(), Map.of(stored, TransactionKind.TRANSFER), null, List.of());
        assertThat(ImportPlanner.plan(List.of(in, currentLeg), ctx).rows()).singleElement().satisfies(t -> {
            assertThat(t.kind()).isEqualTo(TransactionKind.TRANSFER);
            assertThat(t.rowId()).isEqualTo(stored);
            assertThat(t.status()).isEqualTo(RowStatus.ALREADY_IMPORTED);
        });
    }

    @Test
    void pocketDeposit_bothLegsImported_onlySavingsLegLater_alreadyImported() {
        RevolutRow in = leg("Poupanças", "2026-11-26 14:34:39", RESERVA, "250.00");
        RevolutRow out = leg("Current", "2026-11-26 14:34:39", RESERVA, "-250.00");
        String stored = ImportPlanner.plan(List.of(in, out), pocketCtx()).rows().get(0).rowId();
        PlanContext ctx = pocketCtx(pocketMapping(), Map.of(stored, TransactionKind.TRANSFER), null, List.of());
        assertThat(ImportPlanner.plan(List.of(in), ctx).rows()).singleElement()
                .satisfies(t -> assertThat(t.status()).isEqualTo(RowStatus.ALREADY_IMPORTED));
    }

    @Test
    void pocketDeposit_currentLegStoredAsExpense_oneAlreadyImportedRow() {
        RevolutRow in = leg("Poupanças", "2026-11-26 14:34:39", RESERVA, "250.00");
        RevolutRow out = leg("Current", "2026-11-26 14:34:39", RESERVA, "-250.00");
        String stored = RowFingerprints.of(List.of(out)).get(0);
        PlanContext ctx = pocketCtx(pocketMapping(), Map.of(stored, TransactionKind.EXPENSE), null, List.of());
        assertThat(ImportPlanner.plan(List.of(in, out), ctx).rows()).singleElement().satisfies(t -> {
            assertThat(t.rowId()).isEqualTo(stored);
            assertThat(t.status()).isEqualTo(RowStatus.ALREADY_IMPORTED);
        });
    }

    @Test
    void pocketDeposit_onlyCurrentLeg_usesRememberedPocketsAccount() {
        UUID remembered = UUID.randomUUID();
        RevolutRow out = leg("Current", "2026-11-26 14:34:39", RESERVA, "-250.00");
        PlanContext ctx = pocketCtx(Map.of("Current", CURRENT), Map.of(), remembered, List.of());
        assertThat(ImportPlanner.plan(List.of(out), ctx).rows()).singleElement().satisfies(t -> {
            assertThat(t.kind()).isEqualTo(TransactionKind.TRANSFER);
            assertThat(t.bankAccountId()).isEqualTo(CURRENT);
            assertThat(t.destinationBankAccountId()).isEqualTo(remembered);
            assertThat(t.rowId()).isEqualTo(RowFingerprints.of(List.of(out)).get(0));
            assertThat(t.pocket().direction()).isEqualTo(PocketInfo.Direction.INTO);
        });
    }

    @Test
    void pocketRow_noPocketsAccount_skipped() {
        RevolutRow out = leg("Current", "2026-11-26 14:34:39", RESERVA, "-250.00");
        ImportPlan noPockets =
                ImportPlanner.plan(List.of(out), pocketCtx(Map.of("Current", CURRENT), Map.of(), null, List.of()));
        assertThat(noPockets.rows()).isEmpty();
        assertThat(noPockets.skipped()).extracting(SkippedRow::reason)
                .containsExactly(SkipReason.POCKET_NO_ACCOUNT);

        RevolutRow in = leg("Poupanças", "2026-11-26 14:34:39", RESERVA, "250.00");
        ImportPlan noCurrent =
                ImportPlanner.plan(List.of(in), pocketCtx(Map.of("Poupanças", POCKETS), Map.of(), null, List.of()));
        assertThat(noCurrent.rows()).isEmpty();
        assertThat(noCurrent.skipped()).extracting(SkippedRow::reason)
                .containsExactly(SkipReason.POCKET_NO_ACCOUNT);
    }

    @Test
    void pocketRow_pocketsAccountIsCurrent_skipped() {
        RevolutRow in = leg("Poupanças", "2026-11-26 14:34:39", RESERVA, "250.00");
        RevolutRow out = leg("Current", "2026-11-26 14:34:39", RESERVA, "-250.00");
        RevolutRow lone = leg("Poupanças", "2026-11-26 14:55:30", RESERVA, "1000.00");
        PlanContext mappedToCurrent =
                pocketCtx(Map.of("Current", CURRENT, "Poupanças", CURRENT), Map.of(), null, List.of());
        ImportPlan viaMapping = ImportPlanner.plan(List.of(in, out, lone), mappedToCurrent);
        assertThat(viaMapping.rows()).isEmpty();
        assertThat(viaMapping.skipped()).extracting(SkippedRow::reason).containsExactly(
                SkipReason.POCKET_SAME_ACCOUNT, SkipReason.POCKET_SAME_ACCOUNT, SkipReason.POCKET_SAME_ACCOUNT);

        // Savings product unmapped: the remembered pockets account is the Current account.
        RevolutRow loneCurrent = leg("Current", "2026-11-26 14:54:41", SETUP, "-100.00");
        PlanContext rememberedCurrent = pocketCtx(Map.of("Current", CURRENT), Map.of(), CURRENT, List.of());
        ImportPlan viaRemembered = ImportPlanner.plan(List.of(in, out, loneCurrent), rememberedCurrent);
        assertThat(viaRemembered.rows()).isEmpty();
        assertThat(viaRemembered.skipped()).extracting(SkippedRow::reason).containsExactly(
                SkipReason.POCKET_SAME_ACCOUNT, SkipReason.POCKET_SAME_ACCOUNT, SkipReason.POCKET_SAME_ACCOUNT);
    }

    @Test
    void pocketMatch_linked_nameMatch_none() {
        UUID linked = UUID.randomUUID();
        UUID sameName = UUID.randomUUID();
        UUID setup = UUID.randomUUID();
        List<PlanContext.GoalRef> goals = List.of(
                new PlanContext.GoalRef(sameName, "Reserva de Emergência", null),
                new PlanContext.GoalRef(linked, "Fundo", " reserva de EMERGENCIA "),
                new PlanContext.GoalRef(setup, "setup", null));
        RevolutRow reserva = leg("Poupanças", "2026-11-26 14:34:39", RESERVA, "250.00");
        RevolutRow setupRow = leg("Poupanças", "2026-11-26 14:54:41", SETUP, "100.00");
        RevolutRow other =
                leg("Poupanças", "2026-11-26 14:58:00", "Carregamento de subconta EUR Carro de EUR", "40.00");
        List<PlannedRow> rows = ImportPlanner.plan(
                        List.of(reserva, setupRow, other), pocketCtx(pocketMapping(), Map.of(), null, goals))
                .rows();
        assertThat(rows).extracting(r -> r.pocket().match()).containsExactly(PocketInfo.Match.LINKED,
                PocketInfo.Match.NAME_MATCH, PocketInfo.Match.NONE);
        assertThat(rows).extracting(r -> r.pocket().goalId()).containsExactly(linked, setup, null);
        assertThat(rows).extracting(r -> r.pocket().name()).containsExactly("Reserva de Emergência", "Setup",
                "Carro");
    }

    @Test
    void pocketDeposit_englishExport_matchesGoalByName() {
        String wording = "To pocket EUR Setup from EUR";
        RevolutRow in = row("Savings", "2026-11-26 14:54:41", "2026-11-26 14:54:41", wording, "100.00", 10000, 0,
                "EUR", "COMPLETED", "TRANSFER");
        RevolutRow out = row("Current", "2026-11-26 14:54:41", "2026-11-26 14:54:41", wording, "-100.00", -10000,
                0, "EUR", "COMPLETED", "TRANSFER");
        UUID setup = UUID.randomUUID();
        PlanContext ctx = pocketCtx(Map.of("Current", CURRENT, "Savings", POCKETS), Map.of(), null,
                List.of(new PlanContext.GoalRef(setup, "Setup", null)));
        assertThat(ImportPlanner.plan(List.of(in, out), ctx).rows()).singleElement().satisfies(t -> {
            assertThat(t.kind()).isEqualTo(TransactionKind.TRANSFER);
            assertThat(t.bankAccountId()).isEqualTo(CURRENT);
            assertThat(t.destinationBankAccountId()).isEqualTo(POCKETS);
            assertThat(t.pocket().direction()).isEqualTo(PocketInfo.Direction.INTO);
            assertThat(t.pocket().goalId()).isEqualTo(setup);
        });
    }

    @Test
    void pocketWithdrawal_englishCurrentLegOnly_transferOutOfPockets() {
        RevolutRow in = row("Current", "2026-11-27 09:00:00", "2026-11-27 09:00:00", "Pocket Withdrawal", "40.00",
                4000, 0, "EUR", "COMPLETED", "TRANSFER");
        PlanContext ctx = pocketCtx(Map.of("Current", CURRENT), Map.of(), POCKETS, List.of());
        assertThat(ImportPlanner.plan(List.of(in), ctx).rows()).singleElement().satisfies(t -> {
            assertThat(t.kind()).isEqualTo(TransactionKind.TRANSFER);
            assertThat(t.bankAccountId()).isEqualTo(POCKETS);
            assertThat(t.destinationBankAccountId()).isEqualTo(CURRENT);
            assertThat(t.pocket().direction()).isEqualTo(PocketInfo.Direction.OUT_OF);
            assertThat(t.pocket().name()).isNull();
        });
    }

    @Test
    void pocketWithdrawal_unknownWording_asksForGoal() {
        RevolutRow out = leg("Poupanças", "2026-11-27 09:00:00", "Pocket withdrawal", "-300.00");
        PlanContext ctx = pocketCtx(pocketMapping(), Map.of(), null,
                List.of(new PlanContext.GoalRef(UUID.randomUUID(), "Pocket withdrawal", null)));
        assertThat(ImportPlanner.plan(List.of(out), ctx).rows()).singleElement().satisfies(t -> {
            assertThat(t.kind()).isEqualTo(TransactionKind.TRANSFER);
            assertThat(t.bankAccountId()).isEqualTo(POCKETS);
            assertThat(t.destinationBankAccountId()).isEqualTo(CURRENT);
            assertThat(t.rowId()).isEqualTo(RowFingerprints.of(List.of(out)).get(0));
            assertThat(t.pocket().direction()).isEqualTo(PocketInfo.Direction.OUT_OF);
            assertThat(t.pocket().name()).isNull();
            assertThat(t.pocket().match()).isEqualTo(PocketInfo.Match.NONE);
            assertThat(t.pocket().goalId()).isNull();
        });
    }

    @Test
    void pocketWithdrawal_onlyCurrentLeg_usesSavingsLegFingerprint() {
        RevolutRow back = leg("Current", "2026-11-27 09:00:00", SETUP, "100.00");
        RevolutRow savingsLeg = leg("Poupanças", "2026-11-27 09:00:00", SETUP, "-100.00");
        String expected = RowFingerprints.of(List.of(savingsLeg)).get(0);
        PlanContext first = pocketCtx(Map.of("Current", CURRENT), Map.of(), POCKETS, List.of());
        assertThat(ImportPlanner.plan(List.of(back), first).rows()).singleElement().satisfies(t -> {
            assertThat(t.bankAccountId()).isEqualTo(POCKETS);
            assertThat(t.destinationBankAccountId()).isEqualTo(CURRENT);
            assertThat(t.rowId()).isEqualTo(expected);
            assertThat(t.pocket().direction()).isEqualTo(PocketInfo.Direction.OUT_OF);
        });
        PlanContext later = pocketCtx(pocketMapping(), Map.of(expected, TransactionKind.TRANSFER), null, List.of());
        assertThat(ImportPlanner.plan(List.of(savingsLeg, back), later).rows()).singleElement()
                .satisfies(t -> assertThat(t.status()).isEqualTo(RowStatus.ALREADY_IMPORTED));
    }

    @Test
    void pocketRows_savingsInterest_plannedAsToday() {
        RevolutRow interest = row("Poupanças", "2026-11-30 03:00:00", "2026-11-30 03:00:00", "Juros brutos", "0.12",
                12, 0, "EUR", "COMPLETED", "Juros");
        assertThat(ImportPlanner.plan(List.of(interest), pocketCtx()).rows()).singleElement().satisfies(r -> {
            assertThat(r.kind()).isEqualTo(TransactionKind.INCOME);
            assertThat(r.bankAccountId()).isEqualTo(POCKETS);
            assertThat(r.pocket()).isNull();
        });
    }
}
