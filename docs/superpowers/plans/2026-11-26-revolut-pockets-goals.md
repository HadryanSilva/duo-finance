# Revolut Pockets → Goals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Goals take any number of dated contributions, with a monthly target that tracks progress. Importing a Revolut statement turns pocket moves into transfers linked to goal contributions and withdrawals.

**Architecture:**
- **Part 1 (Tasks 1–4)** reshapes `goal_movements`: many CONTRIBUTIONs per month, each with a date; DEPOSIT folded into CONTRIBUTION; SKIP still one per month. The overview's `suggestions` become `monthTargets`.
- **Part 2 (Tasks 5–9)** teaches the pure `ImportPlanner` to recognise pocket rows (always a TRANSFER, even with one leg missing, using the missing leg's fingerprint). Preview rows carry pocket info. The commit writes a goal movement linked to its transaction (`goal_movements.transaction_id`, ON DELETE CASCADE), and the review UI picks or creates the goal.

**Tech Stack:** Spring Boot 3 / Java 21, JPA + NamedParameterJdbcTemplate, Postgres + Flyway, JUnit with `@IntegrationTest` (Testcontainers); Vue 3 + TanStack Query, vitest + MSW, vue-i18n.

**Spec:** `docs/superpowers/specs/2026-11-26-revolut-pockets-goals-design.md` (the "Claude's calls" list is binding).

## Global Constraints

- **One migration, V10** (`backend/src/main/resources/db/migration/V10__goal_contributions_and_pockets.sql`). V1–V9 are frozen.
- **Money:** integer cents, EUR. **Dates:** `LocalDate`. "Current month" checks use `shared/time/RunningMonths`, never `YearMonth.now(clock)` directly.
- **Fingerprints:** stored import fingerprints must not change. Do NOT alias the product `Poupanças` in `RevolutCsvParser`; recognise the savings product by name instead (`Poupanças`, `Savings`).
- **Pocket-name pattern (pt-PT):** `^Carregamento de subconta [A-Z]{3} (.+) de [A-Z]{3}$`. Transfer `Type` values: `Transferência`, `Transfer`.
- **Pocket matching:** lower-cased, accents stripped (NFD, drop `\p{M}`), trimmed.
- **New error codes:**
  - `GOAL_MONTH_HAS_CONTRIBUTIONS` (409)
  - `GOAL_MOVEMENT_IMPORTED` (409)
  - `GOAL_POCKET_TAKEN` (409)

  Add each to the backend `ErrorCode`, the frontend `error-codes.ts` (its count test goes 51 → 54) and both locales.
- **New skip reason:** `POCKET_NO_ACCOUNT`.
- **i18n:** pt-BR and en for every string; key parity is enforced.
- **Frontend:** follow the design guard (tokens; `PageHeader`; `NativeSelect`/`Checkbox`; no raw controls).
- **API types:** after backend DTO changes, run `npm run api:types` against a running backend. If none runs, hand-edit `frontend/src/shared/api/schema.ts` to match the DTOs exactly, and say so in the commit message.
- **Commands:**
  - backend: `cd backend && ./mvnw -q test -Dtest=<Class>` (whole suite: `./mvnw -q verify`)
  - frontend: `cd frontend && npx vitest run <path>`, `npx eslint .`, `npx vue-tsc -b`
- **Commits:** prefix commands with `rtk`. End every message with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Re-importing an overlapping statement after a pocket deposit** must create no second transfer or contribution, including when the first import had only the Savings leg and the second has both. Pinned in Task 6 (`pocketDeposit_partnerArrivesLater_alreadyImported`) and Task 7 (`commit_reimport_noSecondMovement`).
2. **Deleting a pocket transfer or undoing its import batch** removes the contribution and lowers the goal's saved amount. Pinned in Task 8.
3. **An imported withdrawal larger than the saved amount** records only the saved amount, never makes saved negative, and a goal with 0 saved gets no movement. Pinned in Task 7.
4. **Old data after V10:** a DEPOSIT becomes a CONTRIBUTION with the same amount and its creation day; a CONTRIBUTION keeps its month; saved totals do not change. Pinned in Task 1.
5. **A goal created from the review on the import day** accepts the statement's earlier-month deposits, and the projection's first month moves back accordingly. Pinned in Task 7 (`commit_goalCreatedToday_acceptsEarlierMonths`).

---

## Part 1: Contributions without a monthly limit

### Task 1: V10 migration and movement model

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__goal_contributions_and_pockets.sql`
- Modify: `backend/src/main/java/br/dev/duofinance/goal/model/GoalMovement.java`, `GoalMovementType.java`, `Goal.java`; `account/model/Account.java`
- Test: `backend/src/test/java/br/dev/duofinance/db/GoalContributionsBackfillTest.java` (pattern: `OnboardingBackfillTest`)

**Interfaces:**
- Produces:
  - `GoalMovementType { CONTRIBUTION, SKIP, WITHDRAWAL }` (DEPOSIT removed);
  - `GoalMovement` gains `LocalDate occurredOn` (not null) and `@Nullable UUID transactionId`, with a constructor taking both;
  - `Goal` gains `@Nullable String revolutPocket`;
  - `Account` gains `@Nullable UUID pocketsBankAccountId`.
- V10 contents, in order:
  1. `goal_movements.occurred_on date`, backfilled from `month` for CONTRIBUTION/SKIP and from `created_at::date` otherwise, then set NOT NULL;
  2. `UPDATE ... SET type='CONTRIBUTION', month=date_trunc('month', occurred_on) WHERE type='DEPOSIT'`;
  3. replace the type check with `('CONTRIBUTION','SKIP','WITHDRAWAL')` and the month check with `(type IN ('CONTRIBUTION','SKIP')) = (month IS NOT NULL)`;
  4. drop `uq_goal_movements_month` and create `uq_goal_movements_skip_month ON (goal_id, month) WHERE type = 'SKIP'`;
  5. add `transaction_id uuid REFERENCES transactions(id) ON DELETE CASCADE`, unique where not null;
  6. add `goals.revolut_pocket varchar(100)`;
  7. add `accounts.pockets_bank_account_id uuid REFERENCES bank_accounts(id) ON DELETE SET NULL`.

- [ ] **Step 1: Write the failing test** `GoalContributionsBackfillTest.v10_foldsDepositsAndKeepsSavedTotals`:
  - migrate to target `9`;
  - insert an account, a goal, a CONTRIBUTION (month `2026-10-01`, 30000), a DEPOSIT (created `2026-11-07T10:00:00Z`, 5000) and a WITHDRAWAL (created `2026-11-08`, 2000);
  - migrate fully;
  - assert the DEPOSIT row is now `type='CONTRIBUTION'`, `month=2026-11-01`, `occurred_on=2026-11-07`;
  - assert the old contribution has `occurred_on=2026-10-01`;
  - assert the sum (CONTRIBUTION − WITHDRAWAL) is `33000`;
  - assert a second CONTRIBUTION for `2026-11-01` inserts fine, while a second SKIP for the same month fails with `DataIntegrityViolationException`.
- [ ] **Step 2: Run, expect FAIL:** `./mvnw -q test -Dtest=GoalContributionsBackfillTest` → fails (no V10).
- [ ] **Step 3: Write V10** as listed under Interfaces, then update the entities and enum. Remove every `DEPOSIT` reference: `GoalSavedQuery` SQL and the `GoalMovementService.deposit` body. The deposit endpoint itself goes in Task 2; until then `deposit` records a CONTRIBUTION dated today.
- [ ] **Step 4: Run, expect PASS:** same command → PASS. Then `./mvnw -q verify` → green, with any compile fallout from removing DEPOSIT fixed.
- [ ] **Step 5: Commit** `feat(api): V10 — many contributions per month, movement dates, pocket columns`.

### Task 2: Contribution, skip and undo rules

**Files:**
- Modify: `goal/service/GoalMovementService.java`, `goal/controller/GoalController.java`, `goal/dto/ContributeRequest.java`, `goal/dto/GoalMovementResponse.java`, `goal/repository/GoalMovementRepository.java`, `shared/error/ErrorCode.java`
- Delete: the `POST /goals/{id}/deposits` endpoint and the service's `deposit`
- Test: `goal/controller/GoalMovementControllerTest.java` (rewrite the contribution cases)

**Interfaces:**
- Consumes: Task 1 entities.
- Produces:
  - `ContributeRequest(@NotNull @Positive Long amountCents, @Nullable LocalDate date, @Nullable @Size(max=200) String note)`, where a null `date` means today in the server clock;
  - `GoalMovementResponse` gains `LocalDate occurredOn` and `boolean imported` (= `transactionId != null`);
  - `GoalMovementService.recordImported(Goal goal, UUID userId, GoalMovementType type, long amountCents, LocalDate date, UUID transactionId): void`, used by Task 7 and enforcing no cap;
  - `GoalMovementService.followTransaction(UUID transactionId, TransactionKind kind, long amountCents, LocalDate date): void`, used by Task 8.
- Rules:
  - **Contribute:** valid for a date from `RunningMonths.earliestOf(goal.createdAt)` back to any month that already has a movement, up to the current running month; no salary needed; the amount must be ≤ remaining for manual entry (`GOAL_AMOUNT_INVALID`); it deletes that month's SKIP if one exists.
  - **Skip:** a month with a SKIP → `GOAL_MONTH_TAKEN`; a month with contributions → `GOAL_MONTH_HAS_CONTRIBUTIONS`.
  - **Undo:** a movement with `transactionId` → `GOAL_MOVEMENT_IMPORTED`.

- [ ] **Step 1: Write the failing tests** in `GoalMovementControllerTest`:
  - `contribute_twiceSameMonth_bothStored`: 2 POSTs of 10000 and 5000 → 201 both, and the goal's `savedCents` is 15000;
  - `contribute_withDate_usesItsMonth`: `date: 2026-11-03` → the movement has `month=2026-11` and `occurredOn=2026-11-03`;
  - `contribute_noSalary_allowed`: no salary category → 201;
  - `contribute_intoSkippedMonth_removesSkip`: skip, then contribute → movements show no SKIP for the month;
  - `skip_monthWithContribution_conflict`: → 409 `GOAL_MONTH_HAS_CONTRIBUTIONS`;
  - `skip_twice_conflict`: → 409 `GOAL_MONTH_TAKEN`;
  - `contribute_aboveRemaining_rejected` (kept);
  - `contribute_futureDate_rejected`: → 400 `GOAL_MONTH_INVALID`;
  - `undo_importedMovement_conflict`: insert a movement with a `transaction_id` through `jdbc` → DELETE → 409 `GOAL_MOVEMENT_IMPORTED`;
  - `deposits_endpointGone`: POST `/deposits` → 404 or 405.
- [ ] **Step 2: Run, expect FAIL:** `./mvnw -q test -Dtest=GoalMovementControllerTest`.
- [ ] **Step 3: Implement the rules above**, plus `recordImported` and `followTransaction` (Task 8 tests the latter). `followTransaction` deletes the movement when `kind != TRANSFER`; otherwise it sets the amount, `occurredOn` and month (CONTRIBUTION), keeping a WITHDRAWAL's month null.
- [ ] **Step 4: Run, expect PASS:** same command, then `./mvnw -q verify`.
- [ ] **Step 5: Commit** `feat(api): any number of dated goal contributions`.

### Task 3: Monthly targets and pace

**Files:**
- Modify: `goal/repository/GoalSavedQuery.java`, `goal/service/GoalService.java`, `goal/service/GoalProjections.java`, `goal/dto/GoalsOverview.java`
- Create: `goal/dto/GoalMonthTarget.java`
- Delete: `goal/dto/GoalSuggestion.java`
- Test: `goal/controller/GoalOverviewTest.java`, `goal/service/GoalProjectionTest.java`

**Interfaces:**
- Produces:
  - `GoalMonthTarget(UUID goalId, String goalName, YearMonth month, int monthlyPercent, long targetCents, long contributedCents, boolean skipped)`;
  - `GoalsOverview.suggestions` is replaced by `List<GoalMonthTarget> monthTargets`: every active goal with `monthlyPercent > 0` whose first month ≤ month, for a non-future month. `targetCents` = `suggestedCents(salary, %, remaining + contributedCents)`, so contributing does not shrink the target; it is 0 without a salary;
  - `GoalSavedQuery.contributedIn(UUID accountId, YearMonth month): Map<UUID, Long>`;
  - `GoalSavedQuery.skippedIn(UUID accountId, YearMonth month): Set<UUID>`;
  - `GoalSavedQuery.lastMonthlyTotals(UUID accountId): Map<UUID, List<Long>>`, giving per goal the Σ CONTRIBUTION of its last 3 months that had any, newest first. It replaces `lastContributionsByGoal` and `goalsDecidedIn`;
  - `GoalProjections.of(...)` keeps its signature. The caller passes `decidedThisMonth = skipped || (target > 0 ? contributed >= target : contributed > 0)` and the monthly totals as `lastContributions`.
- A goal's first month = earliest of `RunningMonths.earliestOf(createdAt)` and its earliest movement's month. Use it for month targets and for `requireFreeMonth` in Task 2.

- [ ] **Step 1: Write the failing tests:**
  - `overview_monthTarget_tracksProgress`: salary 300000, goal 10% → target 30000; contributions 10000 + 5000 → `contributedCents` 15000, and it stays listed;
  - `overview_monthTarget_metStaysListed`: 30000 contributed → listed with `contributedCents >= targetCents`;
  - `overview_skippedMonth_flagged`;
  - `overview_noSalary_targetZero`;
  - `GoalProjectionTest.pace_usesMonthlyTotals`: totals `[125000, 30000]` → pace month from their average (77500) on the remaining amount.
- [ ] **Step 2: Run, expect FAIL:** `./mvnw -q test -Dtest='GoalOverviewTest,GoalProjectionTest'`.
- [ ] **Step 3: Implement** the queries, `monthTargets` and the first-month rule. Remove `GoalSuggestion` and its usages, including the dashboard's `GoalsOverview` use.
- [ ] **Step 4: Run, expect PASS**, then `./mvnw -q verify`.
- [ ] **Step 5: Commit** `feat(api): monthly goal targets with progress`.

### Task 4: Goals UI for Part 1

**Files:**
- Modify: `frontend/src/shared/api/schema.ts`, `shared/api/types.ts` (`GoalMonthTarget` replaces `GoalSuggestion`), `shared/api/queries/goals.ts`, `shared/i18n/error-codes.ts`, both locales, `features/goals/components/GoalRow.vue`, `GoalAmountForm.vue`, `GoalMovements.vue`, `pages/GoalsPage.vue`, `features/dashboard/components/GoalsCard.vue`
- Create: `features/goals/components/GoalMonthTargets.vue` (replaces `GoalSuggestions.vue`; delete that file and move its spec to `GoalMonthTargets.spec.ts`)
- Test: `GoalMonthTargets.spec.ts`, `pages/GoalsPage.spec.ts`, `dashboard/components/GoalsCard.spec.ts`

**Interfaces:**
- Consumes: Task 2 and 3 API shapes.
- Produces:
  - `useContribute()` with mutation `{ id, body: { amountCents, date?, note? } }`;
  - `useDeposit` removed;
  - in `GoalRow`, the action key `deposit` is renamed `contribute` (label "Registrar contribuição" / "Add contribution", icon `PiggyBank`);
  - `GoalAmountForm` gains a date field (TextField `type="date"`, default today, max today) in contribute mode.
- `GoalMonthTargets` row: name, `MoneyText contributed` "de" `MoneyText target`, and a progress bar (reuse `BudgetProgressBar` if its props fit, else `progress`). Actions:
  - met → ✓ "Meta do mês atingida" / "Month's target met";
  - not met → "Registrar" (opens the contribute form pre-filled with `target − contributed`) and "Pular" (hidden once there are contributions);
  - skipped → "Pulado este mês" / "Skipped this month".
- `GoalMovements`: an imported movement shows a "Importado" badge and no undo button; dates come from `occurredOn`.

- [ ] **Step 1: Write the failing tests:**
  - `GoalMonthTargets.spec.ts`:
    - `showsProgress`: 15000 of 30000 → text contains "150,00" and "300,00", with a progressbar `aria-valuenow="50"`;
    - `met_showsCheck_noButtons`;
    - `register_prefillsRemaining`: click "Registrar" → the amount field shows 150,00;
    - `skip_hiddenOnceContributed`;
    - `skipped_showsLabel`.
  - `GoalsPage.spec.ts`: `goals_contribute_postsDate`, where the POST body has `date` set to today and the chosen amount.
  - `GoalMovements` (inside `GoalsPage.spec`): `goals_importedMovement_hasNoUndo`.
  - `GoalsCard.spec.ts`: update for `monthTargets`.
  - `i18n.spec` and the error-codes count (54) must pass.
- [ ] **Step 2: Run, expect FAIL:** `npx vitest run src/features/goals src/features/dashboard src/shared/i18n`.
- [ ] **Step 3: Implement** the schema/types, queries, components, copy and error codes.
- [ ] **Step 4: Run, expect PASS:** `npx vitest run`, `npx eslint .`, `npx vue-tsc -b` → all clean.
- [ ] **Step 5: Commit** `feat(web): monthly goal targets and dated contributions`.

---

## Part 2: Pockets in the import

### Task 5: Goal ↔ pocket link

**Files:**
- Create: `backend/.../goal/service/PocketNames.java`
- Modify: `goal/dto/CreateGoalRequest.java`, `UpdateGoalRequest.java`, `GoalResponse.java`, `goal/service/GoalService.java`; frontend `features/goals/components/GoalForm.vue`, both locales
- Test: `goal/controller/GoalControllerTest.java`, `goal/service/PocketNamesTest.java`, `GoalsPage.spec.ts`

**Interfaces:**
- Produces:
  - `PocketNames.normalise(String): String` (NFD, drop `\p{M}`, lower-case `Locale.ROOT`, trim, collapse inner whitespace);
  - `revolutPocket` (`@Nullable @Size(max=100)`, blank → null) on both requests and on `GoalResponse`;
  - `GoalService.requirePocketFree(accountId, pocket, exceptId)` → `GOAL_POCKET_TAKEN` when another active goal has the same normalised pocket;
  - `GoalForm` gets an optional "Pocket da Revolut" field with hint "Nome do pocket no app da Revolut, para ligar as importações" (en: "The pocket's name in the Revolut app, to link imports"), and `initial` gains `revolutPocket`.

- [ ] **Step 1: Write the failing tests:**
  - `PocketNamesTest.normalise_ignoresCaseAccentsAndSpaces`: `"  Reserva de  Emergência "` → `"reserva de emergencia"`;
  - `GoalControllerTest.create_withPocket_returnsIt`;
  - `create_pocketTakenByActiveGoal_conflict` (`"reserva de emergencia"` vs `"Reserva de Emergência"`);
  - `create_pocketOfCompletedGoal_allowed`;
  - `GoalsPage.spec`: `goals_form_sendsPocket`.
- [ ] **Step 2: Run, expect FAIL** (both stacks, as in Tasks 2 and 4).
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `feat: link a goal to a Revolut pocket`.

### Task 6: Planner recognises pocket rows

**Files:**
- Create: `backend/.../imports/service/PocketRows.java`, `imports/service/PocketInfo.java`
- Modify: `ImportPlanner.java`, `PlannedRow.java`, `PlanContext.java`, `SkipReason.java`, `RowFingerprints.java`
- Test: `imports/service/ImportPlannerTest.java`, `imports/service/PocketRowsTest.java`

**Interfaces:**
- Produces:
  - `PocketRows.SAVINGS_PRODUCTS = Set.of("Poupanças", "Savings")`;
  - `PocketRows.pocketName(String description): Optional<String>` (pt-PT pattern);
  - `PocketRows.isPocketRow(RevolutRow row): boolean`, true when either (savings product AND Type ∈ {Transferência, Transfer}) or (product `Current` AND `pocketName` is present);
  - `PocketRows.savingsProductFor(String description): String`, giving `"Poupanças"` when the pt-PT pattern matches and `"Savings"` otherwise;
  - `RowFingerprints.forLeg(RevolutRow row, String product, String amountRaw): String`, the same base as `of`, with occurrence index 0;
  - `record PocketInfo(@Nullable String name, Direction direction, @Nullable UUID goalId, Match match)` with `enum Direction { INTO, OUT_OF }` (INTO = money into the pocket: positive savings leg / negative Current leg) and `enum Match { LINKED, NAME_MATCH, NONE }`;
  - `PlannedRow` gains `@Nullable PocketInfo pocket`;
  - `PlanContext` gains `@Nullable UUID pocketsBankAccountId` and `List<GoalRef> activeGoals`, with `record GoalRef(UUID id, String name, @Nullable String revolutPocket)`;
  - `SkipReason.POCKET_NO_ACCOUNT`.
- Planner rules:
  - **Pairing:** pocket rows pair as today (same started date, opposite amount, different accounts). A pocket row is never INCOME or EXPENSE.
  - **Unpaired pocket row:** becomes a TRANSFER between the Current account and the pockets account. The pockets account is the mapping of the savings product present in the file, else `ctx.pocketsBankAccountId()`; when it or the Current account is unknown, the row is skipped with `POCKET_NO_ACCOUNT`.
  - **Its rowId** is the fingerprint of the negative leg:
    - for INTO with only the savings leg: `forLeg(row, "Current", negate(amountRaw))`;
    - for OUT_OF with only the Current leg: `forLeg(row, savingsProductFor(description), negate(amountRaw))`;
    - otherwise the row's own fingerprint.
  - **Status:** `ALREADY_IMPORTED` when that fingerprint is stored.
  - **Goal match:**
    - `LINKED` when an active goal's normalised `revolutPocket` equals the normalised name;
    - else `NAME_MATCH` when one active goal without a pocket has the same normalised name;
    - else `NONE` with `goalId` null.

    OUT_OF without a parsed name → `NONE`.
  - **Non-pocket savings rows** (interest and the like) are planned as today.

- [ ] **Step 1: Write the failing tests** with the anonymised sample rows from the spec:
  - `PocketRowsTest`:
    - `pocketName_ptPt` → "Reserva de Emergência";
    - `pocketName_other_empty`;
    - `isPocketRow_savingsTransfer_true`;
    - `isPocketRow_currentWithPocketDescription_true`;
    - `isPocketRow_savingsInterest_false`.
  - `ImportPlannerTest`:
    - `pocketDeposit_bothLegs_oneTransferIntoPockets`: the −250 Current and +250 Poupanças legs give 1 TRANSFER, from Current to the pockets account, with `pocket.direction=INTO` and name "Reserva de Emergência";
    - `pocketDeposit_onlySavingsLeg_transferWithCurrentLegFingerprint`: the +1000 row alone → a TRANSFER whose `rowId` equals `RowFingerprints.of(List.of(currentLeg)).get(0)`, where `currentLeg` is the same row with product Current and amount `-1000.00`;
    - `pocketDeposit_partnerArrivesLater_alreadyImported`: with that fingerprint stored as TRANSFER, a file with both legs → 1 row, `ALREADY_IMPORTED`;
    - `pocketDeposit_onlyCurrentLeg_usesRememberedPocketsAccount`: no savings rows, `pocketsBankAccountId` set → TRANSFER to it;
    - `pocketRow_noPocketsAccount_skipped`: → `POCKET_NO_ACCOUNT`;
    - `pocketMatch_linked_nameMatch_none`: three goals cover the three cases;
    - `pocketWithdrawal_unknownWording_asksForGoal`: a negative savings Transferência with an unknown description → `OUT_OF`, `NONE`.
- [ ] **Step 2: Run, expect FAIL:** `./mvnw -q test -Dtest='ImportPlannerTest,PocketRowsTest'`.
- [ ] **Step 3: Implement.** Existing `ImportPlannerTest` cases must stay green unchanged (non-pocket behaviour and fingerprints).
- [ ] **Step 4: Run, expect PASS.**
- [ ] **Step 5: Commit** `feat(api): import plans Revolut pocket moves as transfers`.

### Task 7: Preview and commit with goals

**Files:**
- Modify: `imports/service/ImportContextLoader.java`, `ImportPreviewService.java`, `ImportCommitService.java`, `ImportedTransactionWriter.java`, `imports/dto/PreviewResponse.java`, `imports/dto/RowDecision.java`
- Test: `imports/controller/ImportPreviewTest.java`, `ImportCommitTest.java`

**Interfaces:**
- Consumes: Task 6 planner and context fields; `GoalMovementService.recordImported` (Task 2); `PocketNames` (Task 5).
- Produces:
  - `RowDecision(String rowId, boolean include, @Nullable UUID categoryId, @Nullable UUID goalId)`;
  - `PreviewRow` gains `@Nullable PocketInfo pocket`;
  - `ImportedTransactionWriter.insert(...)` returns `@Nullable UUID` (the new transaction id, null on conflict);
  - `ImportContextLoader.effectiveMapping(bankAccountId, productMapping, @Nullable UUID pocketsAccount, Set<String> productsInFile)` fills an unmapped savings product in the file with `pocketsAccount`. The loader reads `accounts.pockets_bank_account_id` and the active goals;
  - preview and commit accept an optional multipart field `pocketsBankAccountId`. When given, it overrides the remembered pockets account for this import (validated like the other bank accounts), and the commit saves it.
- Commit rules:
  - a `goalId` on a non-pocket row → field error `GOAL_NOT_ALLOWED`;
  - an unknown, foreign or completed goal → `GOAL_INVALID`;
  - after each insert that returned an id, for a pocket row with `goalId`:
    - INTO → `recordImported(CONTRIBUTION, amount)` with no cap;
    - OUT_OF → `recordImported(WITHDRAWAL, min(amount, saved))`, skipped when saved is 0;
    - the date is the row's date;
  - when the goal has no `revolutPocket` and the row has a name not used by another active goal, the name is saved on the goal;
  - when the effective mapping maps a savings product, it is saved to `accounts.pockets_bank_account_id`;
  - everything happens in the existing commit transaction.

- [ ] **Step 1: Write the failing tests:**
  - `ImportPreviewTest`:
    - `preview_pocketRows_carryPocketInfo`;
    - `preview_rememberedPocketsAccount_fillsSavingsMapping`.
  - `ImportCommitTest`:
    - `commit_pocketDeposit_createsLinkedContribution`: the goal's saved goes +250, and the movement has `transaction_id` = the transfer's id;
    - `commit_depositAboveTarget_accepted`: target 1000, deposit 1250 → saved 1250;
    - `commit_withdrawalAboveSaved_clampedToSaved`: saved 100, withdrawal 300 → WITHDRAWAL 100, saved 0;
    - `commit_withdrawal_goalAtZero_noMovement`;
    - `commit_nameMatch_savesPocketOnGoal`;
    - `commit_savingsMapping_remembered`;
    - `commit_pocketsAccountParam_usedAndRemembered`: a file with Current pocket legs only, plus `pocketsBankAccountId` → transfers to it, and the account is saved;
    - `commit_goalOnNonPocketRow_rejected`;
    - `commit_completedGoal_rejected`;
    - `commit_reimport_noSecondMovement`: the same file twice → 1 movement;
    - `commit_goalCreatedToday_acceptsEarlierMonths`: a goal created now, and a statement with an October pocket deposit → CONTRIBUTION with `month=2026-10`, and `GET /goals?month=2026-10` lists its month target.
- [ ] **Step 2: Run, expect FAIL:** `./mvnw -q test -Dtest='ImportPreviewTest,ImportCommitTest'`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS**, then `./mvnw -q verify`.
- [ ] **Step 5: Commit** `feat(api): import writes goal movements for pocket moves`.

### Task 8: Keep movements in step with their transfer

**Files:**
- Modify: `transaction/service/TransactionService.java` (call `GoalMovementService.followTransaction` after `update`)
- Test: `transaction/controller/TransactionCrudTest.java`, `imports/controller/ImportBatchTest.java`

**Interfaces:**
- Consumes: `GoalMovementService.followTransaction` (Task 2). Deletion relies on the V10 `ON DELETE CASCADE`.

- [ ] **Step 1: Write the failing tests:**
  - `TransactionCrudTest.update_linkedTransfer_movesItsContribution`: edit the amount 250 → 200 and the date → the movement is 200 with the new date;
  - `update_linkedTransferToExpense_dropsContribution`;
  - `delete_linkedTransfer_dropsContribution`;
  - `ImportBatchTest.undo_batch_dropsPocketContributions`, where the goal's saved goes back to 0.
- [ ] **Step 2: Run, expect FAIL** (the delete and undo cases may already pass through the cascade; keep them as pins and note it in the ledger).
- [ ] **Step 3: Implement** the `update` hook.
- [ ] **Step 4: Run, expect PASS**, then `./mvnw -q verify`.
- [ ] **Step 5: Commit** `feat(api): pocket contributions follow their transfer`.

### Task 9: Import review UI for pockets

**Files:**
- Modify: `frontend/src/shared/api/schema.ts`, `types.ts`, `features/imports/api/imports-api.ts` (`RowDecision.goalId`), `features/imports/lib/review-state.ts`, `components/ImportReviewRow.vue`, `ImportReviewStep.vue`, `ImportSkippedList.vue` (new reason copy), `pages/ImportPage.vue`, both locales
- Create: `features/imports/components/PocketGoalPicker.vue`
- Test: `features/imports/lib/review-state.spec.ts`, `pages/ImportPage.spec.ts`

**Interfaces:**
- Consumes: Task 7 preview and decision shapes; `useGoals(currentMonth())` for the active goals and their `savedCents`; `useCreateGoal` and `GoalForm` (Task 5).
- Produces:
  - `ReviewRow` gains `goalId: string | null`, initialised from `pocket?.goalId`;
  - `setGoal(rows, rowId, goalId): ReviewRow[]` also applies to the other unlocked rows with the same normalised pocket name;
  - `toDecisions` sends `goalId` only for pocket rows.
- `PocketGoalPicker`:
  - uses `NativeSelect` with the active goals plus "+ Criar objetivo "{name}"" ("+ Create goal "{name}"");
  - creating opens `GoalForm` in a `Dialog` pre-filled with `name` and `revolutPocket`, then selects the new goal; the review state is kept.
- Row copy:
  - INTO: "→ contribuição para {goal}" ("→ contribution to {goal}");
  - OUT_OF: "← retirada de {goal}" ("← withdrawal from {goal}");
  - no goal: the warning "Sem objetivo: este valor continua no saldo livre" ("No goal: this amount stays in the free balance");
  - OUT_OF above the goal's `savedCents`: "Só {saved} estão guardados neste objetivo; a retirada fica em {saved}" ("Only {saved} is saved in this goal; the withdrawal is capped at {saved}").
- `POCKET_NO_ACCOUNT` copy: "Pocket sem conta de pockets definida" ("Pocket move with no pockets account set").
- Upload step: when a preview has `POCKET_NO_ACCOUNT` skips, `ImportUploadStep` shows a `SelectField` "Conta dos pockets" ("Pockets account"). Choosing an account previews again with `pocketsBankAccountId`, and the commit sends it too.

- [ ] **Step 1: Write the failing tests:**
  - `review-state.spec.ts`:
    - `initReview_takesPocketGoal`;
    - `setGoal_appliesToSamePocket`;
    - `toDecisions_sendsGoalOnlyForPocketRows`.
  - `ImportPage.spec.ts`:
    - `import_pocketRow_showsLinkedGoal`;
    - `import_pocketRow_pickGoal_sentInCommit`;
    - `import_pocketRow_createGoal_selectsIt` (MSW POST `/goals` → the new id lands in the commit body);
    - `import_pocketRow_noGoal_warns`;
    - `import_withdrawalAboveSaved_warns`;
    - `import_noPocketsAccount_asksAndRepreviews`: the preview has a `POCKET_NO_ACCOUNT` skip → pick an account → the second preview request carries `pocketsBankAccountId`.
- [ ] **Step 2: Run, expect FAIL:** `npx vitest run src/features/imports`.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run, expect PASS:** `npx vitest run`, `npx eslint .`, `npx vue-tsc -b`, `npm run build`. Then do a visual check of the review row and the dialog at 1280px and 390px, light and dark (preview page or the running app).
- [ ] **Step 5: Commit** `feat(web): pick or create the goal for each pocket move`, then follow the repo rule: push `feature/revolut-pockets` and open a PR against `develop`.
