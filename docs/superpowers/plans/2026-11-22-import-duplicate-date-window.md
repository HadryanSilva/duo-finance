# Import duplicate date window Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A manual transaction dated anywhere from a Revolut row's purchase day to its completion day (inclusive) flags that row as `POSSIBLE_DUPLICATE`.

**Architecture:** `ImportPlanner.build` checks every day in `[startedAt day, dateOf(row)]` against `PlanContext.manualTransactions` instead of only `dateOf(row)`. `ImportContextLoader.manualTransactions` lowers its query bound to the earliest started day so those manual rows are loaded.

**Tech Stack:** Java 21+, Spring Boot, JdbcTemplate, JUnit 5 + AssertJ, Maven (`backend/mvnw`).

**Spec:** `docs/superpowers/specs/2026-11-22-import-duplicate-date-window-design.md`

## Global Constraints

- The stored transaction date stays the completion date (`dateOf(row)`); fingerprints and `ALREADY_IMPORTED` are unchanged.
- Matching keys stay kind, amount and bank account; description is not compared.
- `ALREADY_IMPORTED` still wins over `POSSIBLE_DUPLICATE`.
- Branch `fix/import-duplicate-date-window` from `develop`; PR against `develop`.
- Spotless: run only on touched files (`-DspotlessFiles=`), main is not Spotless-clean.

## Review Focus

- Manual transaction dated **after** the completion day (e.g. user typed it a day late): stays `NEW`. Pinned in Task 1 (`plan_manualAfterCompletionDay_isNew`).
- Row with no completion date or completed on its purchase day: single-day window, behaves as today. Covered by existing `plan_matchingManualTransaction_isPossibleDuplicate` plus Task 1 (`plan_manualBeforePurchaseDay_isNew`).
- Fee row of a flagged card payment: the fee goes through `build` too, so a manual fee of the same amount in the window is flagged; no extra test needed beyond what `build` already shares.
- Integration: a manual row dated before the file's earliest completion day must be loaded by the loader. Pinned in Task 2.

---

### Task 1: Planner checks the purchase-to-completion window

**Files:**
- Modify: `backend/src/main/java/br/dev/duofinance/imports/service/ImportPlanner.java` (`build`, around lines 128–147)
- Test: `backend/src/test/java/br/dev/duofinance/imports/service/ImportPlannerTest.java`

**Interfaces:**
- Consumes: `PlanContext.manualTransactions()` (`Set<DuplicateKey>`), `DuplicateKey(LocalDate date, TransactionKind kind, long amountCents, UUID bankAccountId)` — unchanged.
- Produces: no new public API.

The `card(cents)` helper builds a row started `2026-01-10 10:00:00`, completed `2026-01-11 12:00:00`, product `Current` → account `CURRENT`.

- [ ] **Step 1: Write the failing tests** in `ImportPlannerTest`, next to `plan_matchingManualTransaction_isPossibleDuplicate`:

```java
@Test
void plan_manualOnPurchaseDayBeforeCompletion_isPossibleDuplicate() {
    Set<DuplicateKey> manual = Set.of(new DuplicateKey(LocalDate.of(2026, 1, 10), TransactionKind.EXPENSE, 100, CURRENT));
    assertThat(ImportPlanner.plan(List.of(card(-100)), ctx(Map.of(), Map.of(), manual)).rows().get(0).status())
            .isEqualTo(RowStatus.POSSIBLE_DUPLICATE);
}

@Test
void plan_manualBeforePurchaseDay_isNew() {
    Set<DuplicateKey> manual = Set.of(new DuplicateKey(LocalDate.of(2026, 1, 9), TransactionKind.EXPENSE, 100, CURRENT));
    assertThat(ImportPlanner.plan(List.of(card(-100)), ctx(Map.of(), Map.of(), manual)).rows().get(0).status())
            .isEqualTo(RowStatus.NEW);
}

@Test
void plan_manualAfterCompletionDay_isNew() {
    Set<DuplicateKey> manual = Set.of(new DuplicateKey(LocalDate.of(2026, 1, 12), TransactionKind.EXPENSE, 100, CURRENT));
    assertThat(ImportPlanner.plan(List.of(card(-100)), ctx(Map.of(), Map.of(), manual)).rows().get(0).status())
            .isEqualTo(RowStatus.NEW);
}
```

- [ ] **Step 2: Run and confirm only the first fails**

Run: `cd backend && ./mvnw -q test -Dtest=ImportPlannerTest`
Expected: `plan_manualOnPurchaseDayBeforeCompletion_isPossibleDuplicate` FAILS (expected `POSSIBLE_DUPLICATE`, was `NEW`); the other two pass.

- [ ] **Step 3: Implement** in `ImportPlanner.build`: replace the single `contains(new DuplicateKey(date, ...))` check with a private helper `private static boolean manualInWindow(RevolutRow row, TransactionKind kind, long amount, UUID account, PlanContext ctx)` that loops `d` from `row.startedAt().toLocalDate()` to `dateOf(row)` inclusive and returns true on the first `contains`. Keep `ALREADY_IMPORTED` checked first.

- [ ] **Step 4: Run tests**

Run: `cd backend && ./mvnw -q test -Dtest=ImportPlannerTest`
Expected: all pass, including the existing `plan_matchingManualTransaction_isPossibleDuplicate` and `plan_alreadyImportedWinsOverPossibleDuplicate`.

- [ ] **Step 5: Commit**

```bash
rtk git add backend/src/main/java/br/dev/duofinance/imports/service/ImportPlanner.java backend/src/test/java/br/dev/duofinance/imports/service/ImportPlannerTest.java
rtk git commit -m "fix(imports): flag manual transactions between purchase and completion day as possible duplicates"
```

### Task 2: Loader fetches manual transactions from the earliest purchase day

**Files:**
- Modify: `backend/src/main/java/br/dev/duofinance/imports/service/ImportContextLoader.java` (`manualTransactions`, lines 93–106)
- Test: `backend/src/test/java/br/dev/duofinance/imports/controller/ImportPreviewTest.java`

**Interfaces:**
- Consumes: Task 1's planner behaviour.
- Produces: nothing new.

- [ ] **Step 1: Write the failing integration test** in `ImportPreviewTest`, modelled on `preview_knownFingerprintAndManualMatch_flagged` (same `testUsers`, `fixtures.bankAccount`, `fixtures.category`, `fixtures.transaction`, `preview`, `body`, `row` helpers):

```java
@Test
void preview_manualOnPurchaseDay_flaggedWhenCompletedNextDay() throws Exception {
    TestSession s = testUsers.verified("ana@mail.com");
    UUID bank = fixtures.bankAccount(s, "Revolut", 0);
    UUID category = fixtures.category(s, "Compras", CategoryKind.EXPENSE);
    fixtures.transaction(s, Map.of("kind", "EXPENSE", "amountCents", 6197, "date", "2026-11-15",
            "categoryId", category, "bankAccountId", bank));
    String csv = "Type,Product,Started Date,Completed Date,Description,Amount,Fee,Currency,State,Balance\n"
            + "CARD_PAYMENT,Current,2026-11-15 17:52:08,2026-11-16 09:59:43,Lefties,-61.97,0.00,EUR,COMPLETED,1\n";

    JsonNode root = body(preview(s, csv.getBytes(), bank.toString(), null));

    assertThat(row(root, "Lefties").path("status").asString()).isEqualTo("POSSIBLE_DUPLICATE");
}
```

If the bank account currency fixture is not EUR, use the currency `fixtures.bankAccount` creates (check `preview_knownFingerprintAndManualMatch_flagged`, whose sample is EUR).

- [ ] **Step 2: Run and confirm it fails**

Run: `cd backend && ./mvnw -q test -Dtest=ImportPreviewTest#preview_manualOnPurchaseDay_flaggedWhenCompletedNextDay`
Expected: FAIL, status `NEW` (the query starts at 2026-11-16, so the 15/11 manual row is never loaded).

- [ ] **Step 3: Implement**: in `manualTransactions`, compute `min` from `row.startedAt().toLocalDate()` and keep `max` from the completion-or-started date. Update the loop accordingly; started ≤ completed always, so `max` logic is unchanged.

- [ ] **Step 4: Run the import tests**

Run: `cd backend && ./mvnw -q test -Dtest='ImportPreviewTest,ImportPlannerTest,ImportCommitTest'`
Expected: all pass.

- [ ] **Step 5: Format touched files and commit**

```bash
cd backend && ./mvnw -q spotless:apply -DspotlessFiles='.*ImportPlanner.*\.java,.*ImportContextLoader\.java,.*ImportPreviewTest\.java'
rtk git add backend/src/main/java/br/dev/duofinance/imports/service/ImportContextLoader.java backend/src/test/java/br/dev/duofinance/imports/controller/ImportPreviewTest.java backend/src/main/java/br/dev/duofinance/imports/service/ImportPlanner.java backend/src/test/java/br/dev/duofinance/imports/service/ImportPlannerTest.java
rtk git commit -m "fix(imports): load manual transactions from the earliest purchase day"
```

### Task 3: Spec note and PR

- [ ] **Step 1:** In `docs/superpowers/specs/2026-11-10-revolut-csv-import-design.md`, update the possible-duplicate rule (search for "POSSIBLE_DUPLICATE" or "possível duplicata") to say the date may fall anywhere from the Started Date day to the stored date.
- [ ] **Step 2:** Run the full backend suite: `cd backend && ./mvnw -q verify`. Expected: BUILD SUCCESS.
- [ ] **Step 3:** Commit, push `fix/import-duplicate-date-window`, open a PR against `develop`.
