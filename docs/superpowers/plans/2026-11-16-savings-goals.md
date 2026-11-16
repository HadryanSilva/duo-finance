# Savings Goals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let the couple earmark money for named goals through confirmed monthly contributions (a % of the salary), deposits and withdrawals, and show progress, suggestions and "saldo livre".

**Architecture:** A new backend module `br.dev.duofinance.goal` (controller/dto/model/repository/service, shaped like `budget/`) with migration V8 (goals, goal_movements, accounts.salary_category_id). Suggestions, saved totals, status and projections are computed on read; the dashboard endpoint embeds the month's goals overview and a nullable `freeBalanceCents`. Frontend: shared query hooks in `src/shared/api/queries/goals.ts` (used by the goals page and the dashboard, since features may not import each other), a new `features/goals` page, and a dashboard `GoalsCard` + BalanceCard line.

**Tech Stack:** Spring Boot 4 / Java 25, Spring Data JPA + NamedParameterJdbcTemplate, Flyway, Postgres 16, Testcontainers (`@IntegrationTest`, MockMvcTester); Vue 3, TanStack Query, vee-validate + zod, vue-i18n, Vitest + MSW, Playwright.

**Spec:** `docs/superpowers/specs/2026-11-16-savings-goals-design.md`

## Global Constraints

- Earmark only: goals create no transactions/transfers and change no existing figure (lists, charts, category spending, budgets, total balance).
- Money in EUR cents (`long`); amounts > 0 are capped by `FinanceValidation.MAX_AMOUNT_CENTS`.
- One migration only: `V8__goals.sql`. V1–V7 are frozen.
- Every goal write locks the account row first (`AccountRepository.findByIdForUpdate`), then touches goals/movements — the "account before entity" lock order.
- New error codes go in backend `ErrorCode` and frontend `src/shared/i18n/error-codes.ts`, with pt-BR + en messages; update the count assertion in `src/shared/i18n/i18n.spec.ts` (37 → 45).
- All new UI strings in both `pt-BR.json` and `en.json`. Frontend passes the design guard (no hex, no arbitrary sizes, PageHeader on pages, MoneyText in templates, no cross-feature imports).
- Mobile: forms in `SidePanel`; row actions use `ActionsMenu` below md (pattern of `RecurringRuleRow`).
- Backend: run Spotless only on touched files (`-DspotlessFiles=`); frontend: `prettier --check --end-of-line auto` on touched files (CRLF checkout).
- Backend tests need Docker (Testcontainers). The `api:types` step needs the backend running on :8080 against a throwaway Postgres (not the dev volume) because of V8.

## Review Focus

1. Two members contribute the same month for the same goal at the same time → exactly one succeeds, the other gets `GOAL_MONTH_TAKEN` (account lock + unique index). Pinned in Task 3.
2. Withdraw or undo that would drive saved below 0 → rejected with `GOAL_AMOUNT_INVALID`, saved unchanged. Pinned in Task 3.
3. Salary category archived or deleted after being chosen → no suggestions, overview reports the archived category / null, no 500. Pinned in Task 4.
4. A goal created mid-month M gets a suggestion for M but none for M−1, and none for a future month. Pinned in Task 4.
5. Another account's goal ids on every goal route → 404 and nothing written. Pinned in Task 5 (AccountIsolationTest).

---

### Task 1: Schema, settings endpoint and error codes

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__goals.sql`
- Create: `backend/src/main/java/br/dev/duofinance/goal/model/{Goal.java,GoalMovement.java,GoalMovementType.java}`
- Create: `backend/src/main/java/br/dev/duofinance/goal/repository/{GoalRepository.java,GoalMovementRepository.java,GoalSettingsRepository.java}`
- Create: `backend/src/main/java/br/dev/duofinance/goal/dto/{GoalSettingsResponse.java,UpdateGoalSettingsRequest.java}`
- Create: `backend/src/main/java/br/dev/duofinance/goal/service/GoalSettingsService.java`
- Create: `backend/src/main/java/br/dev/duofinance/goal/controller/GoalController.java`
- Modify: `backend/src/main/java/br/dev/duofinance/shared/error/ErrorCode.java`, `backend/src/main/java/br/dev/duofinance/shared/security/WebConfig.java` (add `"/api/v1/goals/**"` to `FINANCIAL_PATHS`)
- Test: `backend/src/test/java/br/dev/duofinance/goal/controller/GoalSettingsControllerTest.java`

**Interfaces:**
- Produces (SQL, V8):
  - `ALTER TABLE accounts ADD COLUMN salary_category_id uuid REFERENCES categories (id) ON DELETE SET NULL;`
  - `goals(id uuid PK, account_id uuid NOT NULL → accounts ON DELETE CASCADE, name varchar(100) NOT NULL, target_cents bigint NOT NULL CHECK (> 0), target_date date NULL, monthly_percent smallint NOT NULL CHECK (0..100), completed_at timestamptz NULL, created_at, updated_at)`; `CREATE UNIQUE INDEX uq_goals_active_name ON goals (account_id, lower(name)) WHERE completed_at IS NULL;` `CREATE INDEX idx_goals_account_id ON goals (account_id);`
  - `goal_movements(id uuid PK, goal_id uuid NOT NULL → goals ON DELETE CASCADE, account_id uuid NOT NULL → accounts ON DELETE CASCADE, type varchar(16) NOT NULL CHECK (type IN ('CONTRIBUTION','SKIP','DEPOSIT','WITHDRAWAL')), amount_cents bigint NOT NULL, month date NULL, note varchar(200) NULL, created_by uuid NULL → users ON DELETE SET NULL, created_at timestamptz NOT NULL)`; checks: `(type IN ('CONTRIBUTION','SKIP')) = (month IS NOT NULL)`, `(type = 'SKIP' AND amount_cents = 0) OR (type <> 'SKIP' AND amount_cents > 0)`, `month IS NULL OR extract(day FROM month) = 1`; `CREATE UNIQUE INDEX uq_goal_movements_month ON goal_movements (goal_id, month) WHERE month IS NOT NULL;` `CREATE INDEX idx_goal_movements_goal_id ON goal_movements (goal_id);` `CREATE INDEX idx_goal_movements_account_id ON goal_movements (account_id);`
- Produces (Java): entities `Goal` (fields as columns; `isCompleted()`), `GoalMovement` (month as `LocalDate` first day), `enum GoalMovementType { CONTRIBUTION, SKIP, DEPOSIT, WITHDRAWAL }`.
  - `GoalRepository extends JpaRepository<Goal, UUID>`: `List<Goal> findByAccountIdOrderByCreatedAtAsc(UUID accountId)`, `Optional<Goal> findByIdAndAccountId(UUID id, UUID accountId)`.
  - `GoalMovementRepository`: `List<GoalMovement> findByGoalIdOrderByCreatedAtDesc(UUID goalId)`, `Optional<GoalMovement> findByIdAndGoalId(UUID id, UUID goalId)`, `boolean existsByGoalIdAndMonth(UUID goalId, LocalDate month)`.
  - `GoalSettingsRepository` (NamedParameterJdbcTemplate on `accounts.salary_category_id`): `Optional<UUID> salaryCategoryId(UUID accountId)`, `void setSalaryCategoryId(UUID accountId, @Nullable UUID categoryId)`.
  - `GoalSettingsResponse(@Nullable CategoryRef salaryCategory)` reusing the budget `CategoryRef` (id, name, color, icon, archived); `UpdateGoalSettingsRequest(@Nullable UUID salaryCategoryId)`.
  - `GoalSettingsService.get(UUID accountId): GoalSettingsResponse`, `update(UUID accountId, UpdateGoalSettingsRequest): GoalSettingsResponse` — validates with `CategoryService.requireUsable(accountId, id, CategoryKind.INCOME)`, mapping `CATEGORY_KIND_MISMATCH`/`CATEGORY_ARCHIVED` to `GOAL_SALARY_CATEGORY_INVALID`; null clears.
  - Endpoints: `GET /api/v1/goals/settings`, `PUT /api/v1/goals/settings`.
  - New `ErrorCode`s (append before `INTERNAL_ERROR`): `GOAL_NAME_TAKEN(CONFLICT)`, `GOAL_PERCENT_CAP(BAD_REQUEST)`, `GOAL_COMPLETED(CONFLICT)`, `GOAL_MONTH_TAKEN(CONFLICT)`, `GOAL_MONTH_INVALID(BAD_REQUEST)`, `GOAL_AMOUNT_INVALID(BAD_REQUEST)`, `GOAL_NO_SALARY(CONFLICT)`, `GOAL_SALARY_CATEGORY_INVALID(BAD_REQUEST)`.

- [ ] **Step 1: Write failing tests** in `GoalSettingsControllerTest` (`@IntegrationTest`, clock `2027-03-15T10:00:00Z`, helpers like `BudgetControllerTest`):
  - `settings_defaultsToNoSalaryCategory`: `GET /goals/settings` → 200, `salaryCategory` is null.
  - `settings_setIncomeCategory_returnsIt`: create INCOME "Salário" via `fixtures.category`; PUT `{salaryCategoryId}` → 200 with `salaryCategory.name == "Salário"`, `archived == false`; GET returns the same.
  - `settings_expenseCategory_rejected`: EXPENSE category → 400 `GOAL_SALARY_CATEGORY_INVALID`.
  - `settings_archivedCategory_rejected` → 400 `GOAL_SALARY_CATEGORY_INVALID`.
  - `settings_clearWithNull`: PUT `{salaryCategoryId: null}` → `salaryCategory` null.
  - `settings_deletingCategory_clearsIt`: set, then `DELETE /categories/{id}` → GET shows null.
  - `settings_partnerSeesSameSetting`: with `testUsers.couple(...)`, the invitee's GET returns what the inviter set.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=GoalSettingsControllerTest` — Expected: FAIL (404 / compilation).
- [ ] **Step 3: Implement** the migration, entities, repositories, settings service/controller, error codes and the `FINANCIAL_PATHS` entry.
- [ ] **Step 4: Run** `./mvnw -q test -Dtest=GoalSettingsControllerTest,FinanceSchemaTest` — Expected: PASS (V8 applies cleanly on top of V1–V7).
- [ ] **Step 5: Commit** `feat(goals): add goals schema and salary category setting`

### Task 2: Goal CRUD, conclude/reopen, percentage cap

**Files:**
- Create: `backend/src/main/java/br/dev/duofinance/goal/dto/{CreateGoalRequest.java,UpdateGoalRequest.java,GoalResponse.java,GoalStatus.java,GoalProjection.java,GoalsOverview.java,GoalSuggestion.java}`
- Create: `backend/src/main/java/br/dev/duofinance/goal/service/GoalService.java`
- Create: `backend/src/main/java/br/dev/duofinance/goal/repository/GoalSavedQuery.java`
- Modify: `GoalController.java`
- Test: `backend/src/test/java/br/dev/duofinance/goal/controller/GoalControllerTest.java`

**Interfaces:**
- Consumes: Task 1 entities/repositories, `GoalSettingsService`.
- Produces:
  - `CreateGoalRequest(@NotBlank @Size(max=100) String name, @NotNull @Min(1) @Max(MAX_AMOUNT_CENTS) Long targetCents, @Nullable LocalDate targetDate, @NotNull @Min(0) @Max(100) Integer monthlyPercent)`; `UpdateGoalRequest` same fields.
  - `enum GoalStatus { ACTIVE, REACHED, COMPLETED }` — COMPLETED when `completedAt != null`, else REACHED when `saved >= target`, else ACTIVE.
  - `GoalProjection(@Nullable Long requiredMonthlyCents, @Nullable YearMonth projectedMonth)` (filled in Task 4; null here).
  - `GoalResponse(UUID id, String name, long targetCents, @Nullable LocalDate targetDate, int monthlyPercent, GoalStatus status, long savedCents, long remainingCents, @Nullable Instant completedAt, Instant createdAt, @Nullable GoalProjection projection)`; `remainingCents = max(target − saved, 0)`.
  - `GoalSuggestion(UUID goalId, String goalName, YearMonth month, int monthlyPercent, long suggestedCents)`.
  - `GoalsOverview(YearMonth month, @Nullable CategoryRef salaryCategory, long salaryCents, long totalSavedCents, List<GoalResponse> goals, List<GoalResponse> completed, List<GoalSuggestion> suggestions)` — `goals` = non-completed by `createdAt`; `completed` by `completedAt` desc; `totalSavedCents` = Σ saved of non-completed; suggestions empty and salaryCents 0 until Task 4.
  - `GoalSavedQuery.savedByGoal(UUID accountId): Map<UUID, Long>` — one aggregate: Σ CASE type WHEN WITHDRAWAL THEN −amount WHEN SKIP THEN 0 ELSE amount END, grouped by goal_id.
  - `GoalService`: `GoalsOverview overview(UUID accountId, YearMonth month)`, `GoalResponse create(UUID accountId, CreateGoalRequest)`, `update(UUID accountId, UUID id, UpdateGoalRequest)`, `void delete(UUID accountId, UUID id)`, `GoalResponse complete(UUID accountId, UUID id)`, `GoalResponse reopen(UUID accountId, UUID id)`, `YearMonth currentMonth()`.
  - Endpoints: `GET /api/v1/goals?month=yyyy-MM` (default current) → `GoalsOverview`; `POST /goals` → 201; `PUT /goals/{id}`; `DELETE /goals/{id}` → 204; `POST /goals/{id}/complete`; `POST /goals/{id}/reopen`.
  - Rules: name unique among non-completed (case-insensitive) → `GOAL_NAME_TAKEN` (map the `uq_goals_active_name` violation like `BudgetController` does, and also on reopen); Σ `monthlyPercent` of non-completed goals (excluding the goal being edited, including the new value) > 100 → `GOAL_PERCENT_CAP` (create, update, reopen); `PUT` on a COMPLETED goal → `GOAL_COMPLETED`; `complete` on COMPLETED and `reopen` on non-COMPLETED → `GOAL_COMPLETED`.

- [ ] **Step 1: Write failing tests** in `GoalControllerTest` (clock `2027-03-15T10:00:00Z`):
  - `create_returnsActiveGoalWithZeroSaved`: POST `{name:"Videogame novo", targetCents:50000, monthlyPercent:5}` → 201, `status=="ACTIVE"`, `savedCents==0`, `remainingCents==50000`; GET `/goals` lists it in `goals`, `totalSavedCents==0`.
  - `create_duplicateActiveName_conflict`: second "videogame NOVO" → 409 `GOAL_NAME_TAKEN`.
  - `create_percentOver100_rejected`: goals at 60 and 40, then one at 1 → 400 `GOAL_PERCENT_CAP`; one at 0 → 201.
  - `update_ownPercentDoesNotCountTwice`: goal at 60 updated to 100 while alone → 200.
  - `complete_movesToHistory_andReopenRestores`: complete → `status=="COMPLETED"`, appears in `completed`, not in `goals`; PUT on it → 409 `GOAL_COMPLETED`; reopen → `ACTIVE`, back in `goals`.
  - `reopen_overCap_rejected`: complete a 50% goal, create an 60% goal, reopen the first → 400 `GOAL_PERCENT_CAP`, still COMPLETED.
  - `reopen_nameTakenMeanwhile_conflict`: complete "Reserva", create "Reserva", reopen the first → 409 `GOAL_NAME_TAKEN`.
  - `delete_removesGoal` → 204, GET no longer lists it.
  - `validation_rejectsBadInput`: targetCents 0, percent 101, blank name → 400 `VALIDATION_FAILED`.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=GoalControllerTest` — Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces; writes call `accounts.findByIdForUpdate(accountId)` first and `saveAndFlush`.
- [ ] **Step 4: Run** `./mvnw -q test -Dtest=GoalControllerTest,GoalSettingsControllerTest` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(goals): create, edit, conclude and reopen goals`

### Task 3: Movements — contribute, skip, deposit, withdraw, undo

**Files:**
- Create: `backend/src/main/java/br/dev/duofinance/goal/dto/{ContributeRequest.java,SkipRequest.java,GoalAmountRequest.java,GoalMovementResponse.java}`
- Create: `backend/src/main/java/br/dev/duofinance/goal/service/GoalMovementService.java`
- Create: `backend/src/main/java/br/dev/duofinance/goal/repository/GoalSalaryQuery.java`
- Modify: `GoalController.java`
- Test: `backend/src/test/java/br/dev/duofinance/goal/controller/GoalMovementControllerTest.java`

**Interfaces:**
- Consumes: Task 2 `GoalService`/`GoalSavedQuery`, `GoalSettingsRepository`.
- Produces:
  - `GoalSalaryQuery.salary(UUID accountId, UUID categoryId, YearMonth month): long` — Σ `amount_cents` of `kind='INCOME'` transactions of that category dated in the month.
  - `ContributeRequest(@NotNull @DateTimeFormat yyyy-MM YearMonth month, @Nullable @Min(1) @Max(MAX) Long amountCents)`; `SkipRequest(@NotNull YearMonth month)`; `GoalAmountRequest(@NotNull @Min(1) @Max(MAX) Long amountCents, @Nullable @Size(max=200) String note)`.
  - `GoalMovementResponse(UUID id, GoalMovementType type, long amountCents, @Nullable YearMonth month, @Nullable String note, @Nullable TransactionResponse.AuthorRef createdBy, Instant createdAt)` — authors resolved in one `findAllById` like `RecurringRuleResponseAssembler`.
  - `GoalMovementService`: `List<GoalMovementResponse> list(UUID accountId, UUID goalId)`, `GoalResponse contribute(UUID accountId, UUID userId, UUID goalId, ContributeRequest)`, `skip(...)`, `deposit(...)`, `withdraw(...)`, `GoalResponse undo(UUID accountId, UUID goalId, UUID movementId)`.
  - `long suggestedCents(long salaryCents, int percent, long remainingCents)` (package-private static in `GoalService`, reused by Task 4) = `min(round_half_up(salary × percent / 100), remaining)`.
  - Endpoints: `GET /goals/{id}/movements`; `POST /goals/{id}/contributions` → 201 `GoalResponse`; `POST /goals/{id}/skips` → 201; `POST /goals/{id}/deposits` → 201; `POST /goals/{id}/withdrawals` → 201; `DELETE /goals/{id}/movements/{movementId}` → 200 `GoalResponse`.
  - Rules (after locking the account, then loading the goal):
    - any movement write or undo on COMPLETED → `GOAL_COMPLETED`;
    - contribution/skip month must satisfy `YearMonth.from(createdAt at clock zone) <= month <= currentMonth` → else `GOAL_MONTH_INVALID`; an existing CONTRIBUTION/SKIP for the month → `GOAL_MONTH_TAKEN` (also map the `uq_goal_movements_month` violation);
    - contribution needs a usable salary category and `salary(month) > 0` → else `GOAL_NO_SALARY`; amount = request amount or `suggestedCents(...)`; amount must be `1..remaining` → else `GOAL_AMOUNT_INVALID`;
    - deposit `1..remaining`, withdrawal `1..saved` → else `GOAL_AMOUNT_INVALID`;
    - undo that would leave saved < 0 → `GOAL_AMOUNT_INVALID`; movement of another goal → `NOT_FOUND`.

- [ ] **Step 1: Write failing tests** in `GoalMovementControllerTest` (clock `2027-03-15T10:00:00Z`; salary category "Salário" set via settings; incomes `2027-03-05` 200000 and `2027-03-10` 150000):
  - `contribute_withoutAmount_usesSuggestion`: goal target 100000, 10% → POST contributions `{month:"2027-03"}` → 201, `savedCents==35000`; movements list has one CONTRIBUTION 35000, month "2027-03", `createdBy.displayName` of the caller.
  - `contribute_adjustedAmount_isStored`: `{month:"2027-03", amountCents:50000}` → saved 50000.
  - `contribute_aboveRemaining_rejected`: target 10000, `amountCents:10001` → 400 `GOAL_AMOUNT_INVALID`.
  - `contribute_twiceSameMonth_conflict`: second → 409 `GOAL_MONTH_TAKEN`; skip for the same month → 409 `GOAL_MONTH_TAKEN`.
  - `contribute_futureMonth_rejected`: `"2027-04"` → 400 `GOAL_MONTH_INVALID`; `"2027-02"` for a goal created 2027-03-15 → 400 `GOAL_MONTH_INVALID`.
  - `contribute_noSalary_rejected`: settings cleared → 409 `GOAL_NO_SALARY`; with category set but no income in month → 409 `GOAL_NO_SALARY`.
  - `skip_recordsZero`: skip `"2027-03"` → saved unchanged, movement SKIP amount 0.
  - `depositAndWithdraw_adjustSaved`: deposit 20000 note "13º" → 20000; withdraw 5000 → 15000; withdraw 15001 → 400 `GOAL_AMOUNT_INVALID`, saved still 15000.
  - `undo_contribution_bringsMonthBack`: contribute then DELETE the movement → saved 0; contributing `"2027-03"` again → 201.
  - `undo_depositBelowZero_rejected`: deposit 10000, withdraw 8000, undo the deposit → 400 `GOAL_AMOUNT_INVALID`.
  - `movements_onCompletedGoal_rejected`: complete, then deposit → 409 `GOAL_COMPLETED`; undo → 409 `GOAL_COMPLETED`.
  - `reached_whenSavedHitsTarget`: target 20000, deposit 20000 → `status=="REACHED"`, `remainingCents==0`.
  - `concurrentContributions_oneWins` (Review Focus 1): two threads POST the same goal/month via `testUsers.couple`; statuses are exactly {201, 409} and the DB has one CONTRIBUTION for the month.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest=GoalMovementControllerTest` — Expected: FAIL.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** `./mvnw -q test -Dtest='Goal*Test'` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(goals): record contributions, skips, deposits and withdrawals`

### Task 4: Suggestions and projections in the overview

**Files:**
- Modify: `GoalService.java` (overview fills `salaryCategory`, `salaryCents`, `suggestions`, `projection`)
- Test: `backend/src/test/java/br/dev/duofinance/goal/controller/GoalOverviewTest.java`, `backend/src/test/java/br/dev/duofinance/goal/service/GoalProjectionTest.java` (pure unit test)

**Interfaces:**
- Consumes: `GoalSalaryQuery`, `GoalSettingsRepository`, `suggestedCents`.
- Produces:
  - Suggestion for month M per non-completed goal when: settings category exists and is not archived; `salary(M) > 0`; `percent > 0`; `YearMonth(createdAt) <= M <= currentMonth`; status ACTIVE (not REACHED); no CONTRIBUTION/SKIP for M; `suggestedCents > 0`. Ordered by goal `createdAt`.
  - `static @Nullable GoalProjection GoalProjections.of(long targetCents, long savedCents, @Nullable LocalDate targetDate, List<Long> lastContributions /* newest first, ≤3 */, YearMonth current)` in `goal/service/GoalProjections.java`:
    - remaining = target − saved; remaining ≤ 0 → null.
    - targetDate set: months = max(1, months from current to YearMonth(targetDate) inclusive); `requiredMonthlyCents = ceil(remaining / months)`; projectedMonth null.
    - no date: no contributions → null; avg = ceil(Σ/n); `projectedMonth = current.plusMonths(ceil(remaining / avg))`; required null.
  - Projections are only computed for non-completed goals.

- [ ] **Step 1: Write failing tests**
  - `GoalProjectionTest`:
    - `withDate_spreadsRemainingOverMonthsInclusive`: `of(100000, 40000, 2027-08-20, [], 2027-03)` → required 10000.
    - `withPastDate_asksForEverythingNow`: date 2027-01-10 → required 60000.
    - `withoutDate_usesAverageOfLastThree`: `of(120000, 60000, null, [30000, 20000, 10000], 2027-03)` → projected 2027-06.
    - `withoutDate_noContributions_null`, `reached_null`.
  - `GoalOverviewTest` (clock 2027-03-15; salary 200000 + 150000 in 2027-03):
    - `suggestions_percentOfCombinedSalary_cappedAtRemaining`: goal A 10% target 100000 → 35000; goal B 5% target 10000 → 10000.
    - `suggestions_roundHalfUp`: salary 333335, 10% → 33334 (33333.5).
    - `suggestions_disappearAfterConfirmOrSkip`: contribute A, skip B → `suggestions` empty for 2027-03.
    - `suggestions_pastMonthStillOffered`: goal created 2027-02-01 (set clock), income in 2027-02, clock moved to 2027-03-15 → `GET /goals?month=2027-02` suggests it.
    - `suggestions_none_beforeCreationOrInFuture` (Review Focus 4): goal created 2027-03-15 → `month=2027-02` none; `month=2027-04` none.
    - `suggestions_none_whenZeroPercentOrReached`.
    - `suggestions_none_whenSalaryCategoryArchived` (Review Focus 3): archive "Salário" → `salaryCategory.archived==true`, `suggestions` empty, 200.
    - `salaryCategoryDeleted_overviewStillWorks`: delete it (no transactions) → `salaryCategory` null, 200.
    - `overview_reportsProjectionAndTotal`: goal with target date gets `projection.requiredMonthlyCents`; `totalSavedCents` sums non-completed goals only.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest='GoalProjectionTest,GoalOverviewTest'` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `./mvnw -q test -Dtest='Goal*Test'` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(goals): suggest monthly contributions and project goal completion`

### Task 5: Dashboard embedding, saldo livre, isolation

**Files:**
- Modify: `backend/src/main/java/br/dev/duofinance/dashboard/dto/DashboardResponse.java`, `dashboard/service/DashboardService.java`
- Test: `backend/src/test/java/br/dev/duofinance/dashboard/controller/DashboardControllerTest.java`, `backend/src/test/java/br/dev/duofinance/isolation/AccountIsolationTest.java`

**Interfaces:**
- Consumes: `GoalService.overview(accountId, month)`.
- Produces: `DashboardResponse` gains, after `upcoming`: `@Nullable Long freeBalanceCents` (= `totalBalanceCents − goals.totalSavedCents()` only when `month.equals(currentMonth())`, else null) and `GoalsOverview goals`.

- [ ] **Step 1: Write failing tests**
  - `DashboardControllerTest.dashboard_currentMonth_hasFreeBalanceAndGoals`: bank account 300000, goal deposit 50000 → `freeBalanceCents==250000`, `goals.goals[0].savedCents==50000`.
  - `dashboard_pastMonth_omitsFreeBalance`: `month` = previous month → `freeBalanceCents` null, `goals` present.
  - `dashboard_freeBalanceMayBeNegative`: balance 10000, deposits 30000 → −20000.
  - `dashboard_completedGoalReleasesMoney`: complete the goal → `freeBalanceCents==totalBalanceCents`.
  - `AccountIsolationTest`: add `Arguments.of("goals", "PUT", "")`, `("goals","DELETE","")`, `("goals","POST","/complete")`, `("goals","POST","/reopen")`, `("goals","GET","/movements")`, `("goals","POST","/deposits")`, `("goals","POST","/withdrawals")`, `("goals","POST","/contributions")`, `("goals","POST","/skips")` to `idRoutes()` (with valid bodies where the helper needs them); a test that PUT `/goals/settings` with another account's INCOME category → 404 and setting unchanged; `lists_neverShowOtherAccountsData` asserts `GET /goals` of B lists none of A's goals.
- [ ] **Step 2: Run** `./mvnw -q test -Dtest='DashboardControllerTest,AccountIsolationTest'` — Expected: FAIL on new cases.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run the whole backend suite** `./mvnw -q verify` — Expected: BUILD SUCCESS.
- [ ] **Step 5: Commit** `feat(dashboard): show goals and saldo livre`

### Task 6: Frontend API layer, types, errors, i18n, navigation

**Files:**
- Modify: `frontend/src/shared/api/schema.ts` (regenerated), `frontend/src/shared/api/types.ts`
- Create: `frontend/src/shared/api/queries/goals.ts`
- Modify: `frontend/src/shared/i18n/error-codes.ts`, `frontend/src/shared/i18n/i18n.spec.ts` (count 45), `frontend/src/shared/i18n/locales/{pt-BR,en}.json`
- Modify: `frontend/src/app/components/nav.ts`, `frontend/src/app/router.ts`, `frontend/src/app/layouts/AppLayout.spec.ts`
- Create: `frontend/src/features/goals/pages/GoalsPage.vue` (placeholder with `PageHeader` only, filled in Task 7)

**Interfaces:**
- Produces (types.ts): `GoalsOverview`, `GoalResponse`, `GoalSuggestion`, `GoalMovementResponse`, `GoalSettingsResponse`, `CreateGoalRequest`, `UpdateGoalRequest`, `ContributeRequest`, `SkipRequest`, `GoalAmountRequest`, `UpdateGoalSettingsRequest` from `Schemas[...]`.
- Produces (`queries/goals.ts`), all invalidating `['goals']` and `['dashboard']` on success:
  - `useGoals(month: MaybeRefOrGetter<string>)` → `GET /goals?month=`, key `['goals', month]`
  - `useGoalMovements(id: MaybeRefOrGetter<string | null>)` → key `['goals', 'movements', id]`, enabled when id
  - `useGoalSettings()` → key `['goals', 'settings']`; `useUpdateGoalSettings()`
  - `useCreateGoal()`, `useUpdateGoal()` (`{ id, body }`), `useDeleteGoal()`, `useCompleteGoal()`, `useReopenGoal()`
  - `useContribute()` (`{ id, body: ContributeRequest }`), `useSkipMonth()` (`{ id, body: SkipRequest }`), `useDeposit()` / `useWithdraw()` (`{ id, body: GoalAmountRequest }`), `useUndoMovement()` (`{ goalId, movementId }`)
- Nav: `GOALS = item('/goals', 'nav.goals', Target)` (lucide `Target`), inserted in `SIDEBAR_ITEMS` after BUDGETS and in `MORE_ITEMS` after RECURRING. Route `/goals` (auth + onboarded meta like `/budgets`).
- i18n: `nav.goals` "Objetivos" / "Goals"; `errors.GOAL_*` messages (pt-BR): NAME_TAKEN "Já existe um objetivo ativo com esse nome.", PERCENT_CAP "A soma das porcentagens dos objetivos ativos passaria de 100%. Conclua objetivos já atingidos ou reduza as porcentagens.", COMPLETED "Este objetivo está concluído. Reabra-o para alterar.", MONTH_TAKEN "Este mês já foi confirmado ou pulado.", MONTH_INVALID "Mês fora do período do objetivo.", AMOUNT_INVALID "Valor maior do que o permitido para este objetivo.", NO_SALARY "Não há salário registrado neste mês.", SALARY_CATEGORY_INVALID "Escolha uma categoria de receita ativa."; en equivalents.

- [ ] **Step 1: Write failing tests**: `AppLayout.spec.ts` sidebar link list gains "Objetivos" after "Orçamentos"; the user-menu list for USER becomes `['Contas','Categorias','Recorrências','Objetivos','Sair']`; `i18n.spec.ts` count 45 and every `GOAL_*` code has pt-BR and en messages (existing parity test).
- [ ] **Step 2: Run** `rtk vitest run src/app src/shared/i18n` — Expected: FAIL.
- [ ] **Step 3: Regenerate types**: start a throwaway Postgres (`docker run --rm -d -p 55432:5432 -e POSTGRES_PASSWORD=pg postgres:16`), run the backend against it (`SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:55432/postgres`, profile `dev`), `npm run api:types`, stop both. Then implement the hooks, nav, route, i18n and error codes.
- [ ] **Step 4: Run** `rtk vitest run` and `npx vue-tsc -b` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(goals): frontend api, navigation and messages`

### Task 7: Goals page

**Files:**
- Modify: `frontend/src/features/goals/pages/GoalsPage.vue`
- Create: `frontend/src/features/goals/components/{GoalForm.vue,GoalAmountForm.vue,GoalRow.vue,GoalMovements.vue,SalaryCategoryPicker.vue}`, `frontend/src/features/goals/lib/goal-copy.ts`
- Test: `frontend/src/features/goals/pages/GoalsPage.spec.ts`

**Interfaces:**
- Consumes: Task 6 hooks and types; `SidePanel`, `ConfirmDialog`, `MoneyInput`, `MoneyText`, `TextField`, `SelectField`, `ActionsMenu`, `useIsDesktop`, `EmptyState`, `PageHeader`, `BudgetProgressBar` (progress bar, or a goal-coloured variant using `--color-brand`).
- Produces:
  - Page layout: `PageHeader` "Objetivos" with action "Novo objetivo" (the form also opens on `?new=1`, like `/bank-accounts?new=1`); `SalaryCategoryPicker` ("O salário vem da categoria: [select de categorias de receita ativas]", plus a warning when the saved one is archived); list of `GoalRow` for `goals`; section "Concluídos" with `completed` rows (read-only + "Reabrir"); empty state when no goals.
  - `GoalRow`: name, status badge (Atingido / Concluído), `MoneyText` saved / target, progress bar with %, "Faltam € X", "% do salário/mês", projection line (`goal-copy.ts`: `projectionText(p, locale)` → "Para chegar até <data> faltam € X/mês" or "No ritmo atual chega em <mês/ano>"); actions Adicionar valor, Resgatar, Histórico, Editar, Concluir, Excluir (icons ≥ md, `ActionsMenu` below md); completed rows: Reabrir, Histórico, Excluir.
  - `GoalForm` (SidePanel): name, target (`MoneyInput`), optional date, percentage (0–100, hint "0 = sem sugestão mensal"); maps `GOAL_NAME_TAKEN`→name, `GOAL_PERCENT_CAP`→monthlyPercent field errors.
  - `GoalAmountForm` (SidePanel, mode deposit|withdraw): amount + optional note; shows "Disponível: € X" (withdraw) or "Faltam: € X" (deposit).
  - `GoalMovements` (SidePanel): list of movements (type label, month or date, `MoneyText`, `AuthorAvatar`, note) with an "Desfazer" button per row (confirm dialog); hidden undo on completed goals.
  - Concluir: `ConfirmDialog` "Concluir <nome>? € X deixam de ficar separados." ; Excluir: "Excluir <nome>? O histórico será apagado e € X deixam de ficar separados."

- [ ] **Step 1: Write failing tests** in `GoalsPage.spec.ts` (MSW handlers for every goals route; desktop width by default):
  - `goals_listShowsProgressAndProjection`: goal saved 40000 / target 100000 → row text contains "€ 400,00", "€ 1.000,00", "40%", "Faltam € 600,00", the projection sentence.
  - `goals_emptyState_offersCreate`; `goals_newQuery_opensForm` (`/goals?new=1` opens "Novo objetivo").
  - `goals_create_sendsBody`: fill form → POST body `{name:"Videogame novo", targetCents:50000, targetDate:null, monthlyPercent:5}`.
  - `goals_percentCap_showsFieldError`: POST → 400 `GOAL_PERCENT_CAP` → error under "Porcentagem do salário".
  - `goals_salaryCategory_saves`: select "Salário" → PUT `/goals/settings` `{salaryCategoryId:"c2"}`; archived saved category shows the warning.
  - `goals_withdraw_sendsAmountAndNote` and shows "Disponível: € 400,00".
  - `goals_history_undo`: open Histórico → rows with author; Desfazer + confirm → DELETE `/goals/g1/movements/m1`.
  - `goals_complete_confirmsReleasedAmount`: confirm text contains "€ 400,00" → POST `/complete`; completed section shows "Reabrir" → POST `/reopen`.
  - `goals_mobile_actionsMenu` (width 375): "Ações para Videogame novo" menu items `['Adicionar valor','Resgatar','Histórico','Editar','Concluir','Excluir']`.
- [ ] **Step 2: Run** `rtk vitest run src/features/goals` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `rtk vitest run`, `npx vue-tsc -b`, `npx eslint .` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(goals): goals page with history, deposits and withdrawals`

### Task 8: Dashboard goals card and saldo livre

**Files:**
- Create: `frontend/src/features/dashboard/components/GoalsCard.vue`
- Modify: `frontend/src/features/dashboard/components/BalanceCard.vue`, `frontend/src/features/dashboard/pages/DashboardPage.vue`, locales
- Test: `frontend/src/features/dashboard/components/GoalsCard.spec.ts`, `frontend/src/features/dashboard/pages/DashboardPage.spec.ts`

**Interfaces:**
- Consumes: `DashboardResponse.goals`, `DashboardResponse.freeBalanceCents`; `useContribute`, `useSkipMonth` from `@/shared/api/queries/goals`.
- Produces:
  - `BalanceCard`: under the total, when `freeBalanceCents !== null && goals.totalSavedCents > 0`: "Saldo livre" + `MoneyText` + muted "Saldo menos € X separados em objetivos".
  - `GoalsCard` (placed after BudgetsCard in the grid): title "Objetivos", link "Ver todos" → `/goals`.
    - No goals: `CardEmpty` "Crie um objetivo" → `/goals?new=1`.
    - Suggestions block when `goals.suggestions.length`: header "Sugestões de <mês> · Salário € X"; one row per suggestion: name, "<p>% → € Y", buttons Confirmar (POST contributions `{month}`), Ajustar (inline `MoneyInput` + Salvar → `{month, amountCents}`), Pular (POST skips `{month}`); errors shown inline via `errorMessage`.
    - Missing salary category with goals having percent > 0: hint "Escolha a categoria do salário para receber sugestões" → `/goals`.
    - Progress list: each non-completed goal with progress bar and `saved / target`.

- [ ] **Step 1: Write failing tests**
  - `GoalsCard.spec.ts`: `confirm_postsSuggestedMonth` (POST body `{month:"2026-11"}`); `adjust_postsTypedAmount` (`{month:"2026-11", amountCents:30000}`); `skip_postsSkip`; `noSalaryCategory_showsHint`; `empty_offersCreate`; `reached_showsBadge`.
  - `DashboardPage.spec.ts`: `balance_showsFreeBalanceWhenGoalsHoldMoney` ("Saldo livre" and "€ 2.500,00"); `balance_hidesFreeBalanceForPastMonth` (freeBalanceCents null → no "Saldo livre").
- [ ] **Step 2: Run** `rtk vitest run src/features/dashboard` — Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `rtk vitest run`, `npx vue-tsc -b`, `npx eslint .`, `npm run build` — Expected: PASS.
- [ ] **Step 5: Commit** `feat(dashboard): goals card with monthly suggestions and saldo livre`

### Task 9: End-to-end flow

**Files:**
- Create: `frontend/e2e/goals.spec.ts`

- [ ] **Step 1: Write the e2e test** `goals: suggest, confirm, withdraw, conclude` (runs on chromium and mobile projects): register + onboard (as `import.spec.ts`), create INCOME category "Salário" via the categories page, add an income transaction of € 2.000,00 dated today in it, set it as salary category on /goals, create goal "Reserva" target € 5.000,00 at 10%; on /dashboard the card shows "€ 200,00" → Confirmar → card shows the goal at "€ 200,00 / € 5.000,00" and "Saldo livre"; on /goals Resgatar € 50,00 → "€ 150,00"; Concluir → it appears under "Concluídos".
- [ ] **Step 2: Run** `npx playwright test e2e/goals.spec.ts` (dev Postgres up; it runs V8 — use a throwaway database as in Global Constraints if the dev volume must stay on V7) — Expected: PASS on both projects.
- [ ] **Step 3: Commit** `test(goals): end-to-end goal flow`
