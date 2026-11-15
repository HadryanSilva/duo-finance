# Known Gaps and Import Follow-ups Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the four low-risk gaps the MVP reviewers left open and the four deferred Revolut-import follow-ups.

**Architecture:** Nine small, independent fixes. Backend fixes stay inside their feature package (`user`, `recurring`, `imports`, `shared/error`). Two new Flyway migrations (V6 index, V7 onboarding flag). The onboarding flag moves from `localStorage` to `users.onboarded_at`, exposed as `MeResponse.onboarded`. Frontend changes are in `features/imports`, `features/onboarding`, `features/auth`, `features/settings`, `app/guards.ts` and `shared/`.

**Tech Stack:** Java 25, Spring Boot 4, Spring Data JPA / Hibernate 7, Flyway, Postgres 16, Testcontainers; Vue 3, TypeScript, TanStack Query, vue-i18n, Vitest + Testing Library + MSW.

**Spec:** `docs/superpowers/specs/2026-11-05-duo-finance-mvp-design.md` (rules for recurrence, couple bond, deletion, onboarding) and `docs/superpowers/specs/2026-11-10-revolut-csv-import-design.md` (import wizard and commit). The gaps themselves come from the MVP and import final reviews.

**Out of scope:** the pt-PT and pt-BR Revolut values that were guessed and never seen in a real export (`REVERTIDA`, `RECUSADA`, `Câmbio`, `Pendente`, `Revertido(a)`, `Recusado(a)`). They can only be confirmed with a real file, so they get no task. The legal-text placeholders are also out of scope.

## Global Constraints

- Flyway V1–V5 are frozen. New migrations are `V6__category_memory_category_index.sql` and `V7__user_onboarding.sql`.
- `ErrorCode` values are never renamed and no new code is added by this plan.
- Controllers return DTO records only, repositories always scope by `accountId`, and services own `@Transactional`.
- All user-facing copy exists in both `frontend/src/shared/i18n/locales/pt-BR.json` and `en.json`. Strings that vue-i18n would parse (`@ { } |`) are escaped.
- Components use design tokens only: no hex colours and no ad-hoc sizes.
- Run `./mvnw spotless:apply` before each backend commit and `npm run lint` before each frontend commit.
- Prefix shell commands with `rtk` (user's CLAUDE.md).
- Work on a branch `fix/known-gaps-and-import-follow-ups` off `main`. Use Conventional Commits, each ending with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A partner archives a category while the other partner is reviewing an import.** The commit fails on that row. The row must show the problem and keep the rest of the review intact (test in Task 8).
2. **Account deletion runs at 00:15 UTC, while the recurring job is working on that account.** The deletion succeeds, and the job logs nothing and counts no failure (test in Task 2).
3. **After leaving the couple, the user signs in on a second device.** Onboarding is offered there too, because the flag now lives on the server (test in Task 5).
4. **Existing production users after V7.** They must not see onboarding again (backfill test in Task 5).
5. **A weekly rule with `startDate` 1970-01-01.** It is created in one request with every occurrence present, exactly once, and a second run adds nothing (test in Task 3).

---

### Task 1: Deleted user in `MeService` is a stale session, not a wrong password

**Files:**
- Modify: `backend/src/main/java/br/dev/duofinance/user/service/MeService.java:118-120`
- Create: `backend/src/test/java/br/dev/duofinance/user/service/MeServiceTest.java`

**Interfaces:**
- Produces: `MeService.load` throws `ApiException(ErrorCode.SESSION_STALE)` when the user row is gone. Password checks keep `INVALID_CREDENTIALS`.

- [ ] **Step 1: Write the failing test.** This is a plain Mockito unit test. `MeService` is built with mocks for its nine constructor arguments and `Clock.systemUTC()`.

```java
@Test
void get_userDeletedMeanwhile_isSessionStale() {
    when(users.findById(USER_ID)).thenReturn(Optional.empty());
    assertThatThrownBy(() -> service.get(new CurrentUser(USER_ID, ACCOUNT_ID, UserRole.USER)))
            .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.SESSION_STALE));
}

@Test
void delete_userDeletedMeanwhile_isSessionStale() { /* same, calling service.delete(current, "secret123") */ }
```

- [ ] **Step 2: Run it and watch it fail.** Run `cd backend && rtk ./mvnw test -Dtest=MeServiceTest`. Expected: FAIL, because the code is `INVALID_CREDENTIALS`.
- [ ] **Step 3: Implement.** In `load`, throw `new ApiException(ErrorCode.SESSION_STALE)`. This matches `MembershipService.leave`, which already does the same.
- [ ] **Step 4: Run the tests.** Run `rtk ./mvnw test -Dtest='MeServiceTest,MeControllerTest,DeleteMeTest'`. Expected: PASS.
- [ ] **Step 5: Commit** with the message `fix(me): report a user deleted mid-request as a stale session`.

---

### Task 2: The recurring job locks the account before the rule (no deadlock with deletion)

**The deadlock:** a deletion takes `FOR UPDATE` on `accounts` and then cascades into `recurring_rules`. The job does the reverse. It locks the rule (`findByIdForUpdate`), and its `INSERT INTO transactions` then takes the FK `KEY SHARE` lock on `accounts`. The fix is to make the job take the account lock first. Every deletion path (self-deletion, leave and accept-invite) locks or deletes the account row first, so a single lock order covers all of them.

**Files:**
- Modify: `backend/src/main/java/br/dev/duofinance/recurring/repository/RecurringRuleRepository.java`
- Modify: `backend/src/main/java/br/dev/duofinance/recurring/service/RecurringGenerator.java` (`generateDue`)
- Create: `backend/src/test/java/br/dev/duofinance/recurring/service/RecurringDeletionRaceTest.java`

**Interfaces:**
- Produces: `RecurringRuleRepository.lockAccountOfRule(UUID ruleId): Optional<UUID>`, a native query:
  `SELECT a.id FROM accounts a JOIN recurring_rules r ON r.account_id = a.id WHERE r.id = :ruleId FOR KEY SHARE OF a`.
  `KEY SHARE` does not conflict with `PATCH /account` (a non-key update), so renaming the account still works while the job runs.
- `generateDue` first calls `lockAccountOfRule`. If it is empty, the method returns `0`. Then it calls `findByIdForUpdate` as today.

- [ ] **Step 1: Write the failing test.** Use `@IntegrationTest` and the `rule(...)` helper style from `RecurringGenerationJobTest`. The test is `generateDue_whileAccountIsBeingDeleted_waitsAndGeneratesNothing`:
  1. Create a MONTHLY rule starting `2027-03-15` with the clock at `2027-03-15T10:00:00Z`, then move the clock to `2027-04-15T10:00:00Z` so one occurrence is due.
  2. Open a raw connection from the `DataSource` with `autoCommit=false` and run `SELECT id FROM accounts WHERE id = ? FOR UPDATE`.
  3. Start a thread that runs `tx.execute(s -> generator.generateDue(ruleId, LocalDate.parse("2027-04-15")))` and stores the result or the exception.
  4. Poll `pg_stat_activity` (up to 5 s) until another backend has `wait_event_type = 'Lock'`.
  5. On the raw connection, run `SELECT id FROM recurring_rules WHERE id = ? FOR UPDATE NOWAIT`. It must succeed, which proves the job did not take the rule lock first. Then run `DELETE FROM accounts WHERE id = ?` and commit.
  6. Join the thread (10 s). Assert there was no exception, the result is `0`, and `count(*) FROM transactions WHERE account_id = ?` is `0`.
- [ ] **Step 2: Run it and watch it fail.** Run `rtk ./mvnw test -Dtest=RecurringDeletionRaceTest`. Expected: FAIL at step 5 with `55P03 lock_not_available`.
- [ ] **Step 3: Implement** `lockAccountOfRule` and the call at the top of `generateDue`. Update the class Javadoc to state the lock order: account `KEY SHARE`, then rule `FOR UPDATE`.
- [ ] **Step 4: Run the tests.** Run `rtk ./mvnw test -Dtest='RecurringDeletionRaceTest,RecurringGenerationJobTest,RecurringRuleControllerTest,DeleteMeTest,LeaveTest,InvitationTest'`. Expected: PASS.
- [ ] **Step 5: Commit** with the message `fix(recurring): lock the account before the rule so deletion cannot deadlock the job`.

---

### Task 3: Generate all of a rule's occurrences in one statement

**Files:**
- Modify: `backend/src/main/java/br/dev/duofinance/recurring/service/RecurringGenerator.java` (`INSERT_OCCURRENCE` and its loop)
- Test: `backend/src/test/java/br/dev/duofinance/recurring/controller/RecurringRuleControllerTest.java`

**Interfaces:**
- Unchanged: `generateDue(UUID ruleId, LocalDate today): int` still returns the number of rows inserted.

- [ ] **Step 1: Write the regression tests.** These pin the behaviour; they pass on the current code too.
  - `create_weeklyFrom1970_generatesEveryOccurrenceOnce`: clock at `2027-03-15T10:00:00Z`, WEEKLY rule with `startDate` `1970-01-01`. Assert that `count(*)` of the rule's transactions equals `RecurrenceSchedule.between(LocalDate.parse("1970-01-01"), Frequency.WEEKLY, null, LocalDate.parse("1970-01-01"), LocalDate.parse("2027-03-15")).size()`, that `count(DISTINCT date)` equals the same number, and that `job.run()` afterwards reports `generated == 0` for it.
  - `create_monthlyOn31stFrom1970_clampsEveryShortMonth`: assert the generated date for `1970-02` is `1970-02-28` and for `1972-02` is `1972-02-29`.
- [ ] **Step 2: Measure the baseline.** Run `rtk ./mvnw test -Dtest=RecurringRuleControllerTest#create_weeklyFrom1970*` and record the test time from the Surefire report. Expected: PASS. This is the reference for Step 4.
- [ ] **Step 3: Implement.** Replace the per-date loop with one native insert:

```sql
INSERT INTO transactions (id, account_id, kind, amount_cents, date, description, category_id,
                          bank_account_id, destination_bank_account_id, created_by, recurring_rule_id,
                          created_at, updated_at)
SELECT o.id, r.account_id, r.kind, r.amount_cents, o.date, r.description, r.category_id,
       r.bank_account_id, r.destination_bank_account_id, r.created_by, r.id, :now, :now
FROM recurring_rules r
CROSS JOIN unnest(CAST(:ids AS uuid[]), CAST(:dates AS date[])) AS o(id, date)
WHERE r.id = :ruleId
ON CONFLICT (recurring_rule_id, date) WHERE recurring_rule_id IS NOT NULL DO NOTHING
```

  Bind `ids` as a `UUID[]` (one UUID v7 per date, as today) and `dates` as a `LocalDate[]`. Skip the statement when the list of dates is empty. If Hibernate does not bind the arrays, fall back to the JDBC connection (`session.doReturningWork`) with `connection.createArrayOf("uuid", …)` and `createArrayOf("date", …)`; the SQL stays the same.
- [ ] **Step 4: Run the tests.** Run `rtk ./mvnw test -Dtest='RecurringRuleControllerTest,RecurringGenerationJobTest,RecurringDeletionRaceTest'`. Expected: PASS, and the `create_weeklyFrom1970` time is clearly below the Step 2 baseline. Write both times in the commit body.
- [ ] **Step 5: Commit** with the message `perf(recurring): insert a rule's due occurrences in one statement`.

---

### Task 4: Index on `category_memory.category_id`

The FK uses `ON DELETE CASCADE`, so deleting a category currently scans the whole `category_memory` table.

**Files:**
- Create: `backend/src/main/resources/db/migration/V6__category_memory_category_index.sql`
- Test: `backend/src/test/java/br/dev/duofinance/db/ImportSchemaTest.java`

- [ ] **Step 1: Write the failing test** `categoryMemory_hasCategoryIdIndex`. Assert that `SELECT count(*) FROM pg_indexes WHERE tablename = 'category_memory' AND indexname = 'idx_category_memory_category_id'` is `1`.
- [ ] **Step 2: Run it and watch it fail.** Run `rtk ./mvnw test -Dtest=ImportSchemaTest`. Expected: FAIL (0).
- [ ] **Step 3: Implement.** The migration is `CREATE INDEX idx_category_memory_category_id ON category_memory (category_id);`.
- [ ] **Step 4: Run the tests.** Run `rtk ./mvnw test -Dtest='ImportSchemaTest,CategoryControllerTest'` and `rtk bats infra/tests/config.bats`. The infra test expects the highest migration version, so it follows automatically. Expected: PASS.
- [ ] **Step 5: Commit** with the message `perf(imports): index category_memory by category`.

---

### Task 5: Onboarding flag on the server (backend)

**Files:**
- Create: `backend/src/main/resources/db/migration/V7__user_onboarding.sql`
- Modify: `backend/src/main/java/br/dev/duofinance/user/model/User.java` (field `onboardedAt`, `Instant`, nullable)
- Modify: `backend/src/main/java/br/dev/duofinance/user/dto/MeResponse.java` (component `boolean onboarded`, placed after `emailVerified`)
- Modify: `backend/src/main/java/br/dev/duofinance/user/service/MeService.java` (`completeOnboarding`)
- Modify: `backend/src/main/java/br/dev/duofinance/user/controller/MeController.java`
- Modify: `backend/src/main/java/br/dev/duofinance/auth/service/RegistrationService.java` (`joinInvitedAccount`)
- Modify: `backend/src/main/java/br/dev/duofinance/account/service/MembershipService.java` (`leave`)
- Test: `MeControllerTest`, `RegisterTest`, `LeaveTest`, and a new `backend/src/test/java/br/dev/duofinance/db/OnboardingBackfillTest.java`

**Interfaces:**
- Produces: `POST /api/v1/me/onboarding` returns `200 MeResponse`. It is idempotent: the first `onboarded_at` is kept.
- Produces: `MeResponse.onboarded: boolean` (required in OpenAPI). `AuthResponse.user` carries it too.
- Migration: `ALTER TABLE users ADD COLUMN onboarded_at timestamptz; UPDATE users SET onboarded_at = created_at;` The backfill means existing users count as onboarded.
- Rules: registration with an invitation sets `onboarded_at = now`, because the couple already has a name and no partner is left to invite. `leave` sets it back to `null`, because the new solo account is onboarded like a fresh one. Accepting an invitation while signed in does not change it.

- [ ] **Step 1: Write the failing tests.**
  - `MeControllerTest.getMe_newUser_isNotOnboarded`: `$.onboarded` is `false` right after `testUsers.verified(...)`.
  - `MeControllerTest.completeOnboarding_setsFlagAndIsIdempotent`: the first POST returns `$.onboarded == true`. Read `onboarded_at` through `JdbcTemplate`, POST again after `clock` advances one hour, and assert the timestamp did not change.
  - `MeControllerTest.completeOnboarding_withoutToken_returns401`.
  - `RegisterTest.register_withInvitation_isOnboarded`: `$.user.onboarded == true`. A register with an access code gives `false`.
  - `LeaveTest.leave_clearsOnboarded`: after leaving, `$.user.onboarded == false` and the column is `NULL`.
  - `OnboardingBackfillTest.v7_marksExistingUsersOnboarded`: run Flyway on the Testcontainers `DataSource` in a fresh schema `backfill_check` with `target("6")`, insert a `users` row with `created_at = '2026-11-01T00:00Z'`, migrate to latest, and assert `onboarded_at = created_at`. Drop the schema afterwards.
- [ ] **Step 2: Run them and watch them fail.** Run `rtk ./mvnw test -Dtest='MeControllerTest,RegisterTest,LeaveTest,OnboardingBackfillTest'`. Expected: FAIL (no `onboarded` field, 404 on the POST, no V7).
- [ ] **Step 3: Implement** the migration, the entity field, `MeResponse.of` (`user.getOnboardedAt() != null`), `MeService.completeOnboarding(CurrentUser): MeResponse` (`@Transactional`, sets the column only when it is null), the controller method, and the registration and leave changes.
- [ ] **Step 4: Run the tests.** Run `rtk ./mvnw test`. Expected: the full backend suite passes, including `ResponseNullabilityTest`.
- [ ] **Step 5: Commit** with the message `feat(me): store onboarding completion on the server`.

---

### Task 6: Onboarding flag on the server (frontend)

**Files:**
- Regenerate: `frontend/src/shared/api/schema.ts`. Run `npm run api:types` with the backend running (`infra/docker-compose.dev.yml` up, `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`).
- Modify: `frontend/src/shared/api/queries/me.ts`, `frontend/src/app/guards.ts`, `frontend/src/features/onboarding/pages/OnboardingPage.vue`, `frontend/src/features/auth/pages/RegisterPage.vue`, `frontend/src/features/settings/components/CoupleSection.vue`, `frontend/src/test/render.ts`
- Delete: `frontend/src/shared/session/onboarding.ts`
- Test: `guards.spec.ts`, `OnboardingPage.spec.ts`, `RegisterPage.spec.ts`, `LoginPage.spec.ts`, `SettingsPage.spec.ts`

**Interfaces:**
- Consumes: `POST /me/onboarding` returns a `MeResponse` with `onboarded: true` (Task 5).
- Produces: `useCompleteOnboarding()` in `me.ts`, a `useMutation` that posts and then calls `useSession().updateUser(me)` and `qc.setQueryData(['me'], me)`.
- Guard: `if (!exempt && !user.onboarded) return '/onboarding'`.
- `render()` keeps its `onboarded` option (default `true`), which now sets `user.onboarded`. `testUser` gains `onboarded: true`.

- [ ] **Step 1: Update the tests first.** Replace each `isOnboarded`/`markOnboarded` assertion with a server-side one.
  - `OnboardingPage.spec.ts`: `completesAllSteps_andMarksOnboarded` and `skippingSteps_stillMarksOnboarded` assert that an MSW handler for `POST /api/v1/me/onboarding` was called exactly once. The "already a couple / has a bank account" cases assert the same.
  - New `onboardingCompleteFails_stillEntersDashboard`: the handler returns 500 and the route still ends at `/dashboard`.
  - `RegisterPage.spec.ts` `register_afterInvite_goesToDashboard`: there is no client marking, and the server response has `onboarded: true`.
  - `SettingsPage.spec.ts` leave case: goes to `/onboarding` and makes no POST.
  - `guards.spec.ts`: a session user with `onboarded: false` is sent to `/onboarding`, and one with `true` is not.
- [ ] **Step 2: Run them and watch them fail.** Run `cd frontend && rtk npx vitest run src/app src/features/onboarding src/features/auth src/features/settings`. Expected: FAIL.
- [ ] **Step 3: Implement.** `OnboardingPage.finish()` and the early skip await `completeOnboarding.mutateAsync()` and ignore its failure. The guard checks once per app session, so the dashboard is still reachable. Remove the client marking in `RegisterPage` and `CoupleSection`, and delete `onboarding.ts` with every import of it.
- [ ] **Step 4: Run the checks.** Run `rtk npm run test`, `rtk npm run lint` and `rtk npx vue-tsc --noEmit`. Expected: PASS. Then run `rtk npx playwright test e2e/onboarding.spec.ts e2e/couple.spec.ts` against the e2e stack. Expected: PASS.
- [ ] **Step 5: Commit** with the message `feat(onboarding): read and write the onboarding flag through the API`.

---

### Task 7: `IMPORT_ROW_INVALID` reports the line number

**Files:**
- Modify: `backend/src/main/java/br/dev/duofinance/shared/error/ApiException.java`, `GlobalExceptionHandler.java`
- Modify: `backend/src/main/java/br/dev/duofinance/imports/service/RevolutCsvParser.java` (the four `IMPORT_ROW_INVALID` sites)
- Modify: `frontend/src/shared/api/problem.ts`, `frontend/src/shared/api/http.ts`, `frontend/src/features/imports/pages/ImportPage.vue` (`importError`), both locale files
- Test: `RevolutCsvParserTest`, `ImportPreviewTest`, `frontend/src/shared/api/http.spec.ts`, `ImportPage.spec.ts`

**Interfaces:**
- Produces: `ApiException.properties(): Map<String, Object>` (empty by default) and the factory `ApiException.importRowInvalid(int line)`. The factory gives code `IMPORT_ROW_INVALID`, detail `"line " + line` and properties `{"line": line}`. `GlobalExceptionHandler.handleApiException` copies every property onto the `ProblemDetail`.
- `line` is the 1-based physical line where the bad record starts, header included, as `Record.line()` already counts it.
- Produces (frontend): `ApiError.line?: number`, read from the problem body when it is a number.
- Copy: `imports.errors.rowInvalidLine` is "O arquivo tem uma linha com data ou valor ilegível (linha {line})." in pt-BR and "The file has a row with an unreadable date or amount (line {line})." in en.

- [ ] **Step 1: Write the failing tests.**
  - `RevolutCsvParserTest.parse_badAmountOnThirdLine_reportsLine3`: a header plus two rows where row 2 has `Amount` `abc`. Assert the exception has code `IMPORT_ROW_INVALID` and `properties()` equals `Map.of("line", 3)`.
  - `parse_unterminatedQuote_reportsLineWhereRecordStarts`.
  - `ImportPreviewTest.preview_invalidRow_problemCarriesLine`: assert `$.code == "IMPORT_ROW_INVALID"` and `$.line == 3`.
  - `http.spec.ts`: a problem with `line: 3` gives `ApiError.line === 3`.
  - `ImportPage.spec.ts` `upload_rowInvalid_showsLineNumber`: assert the text `(linha 3)` is visible.
- [ ] **Step 2: Run them and watch them fail.** Run `rtk ./mvnw test -Dtest='RevolutCsvParserTest,ImportPreviewTest'` and `rtk npx vitest run src/shared/api src/features/imports`. Expected: FAIL.
- [ ] **Step 3: Implement.** Use the factory at every parser site, copy properties in the handler, add `line` to `ApiError` and http parsing, and in `importError` return `t('imports.errors.rowInvalidLine', { line: e.line })` when the code is `IMPORT_ROW_INVALID` and `line` is set.
- [ ] **Step 4: Run the tests.** Run the full backend suite with `rtk ./mvnw test` (the handler change touches every error) and `rtk npm run test`. Expected: PASS.
- [ ] **Step 5: Commit** with the message `feat(imports): tell the user which line of the statement is unreadable`.

---

### Task 8: Show commit errors on the rows that caused them

The backend already answers `400 VALIDATION_FAILED` with `errors[] = {field: "decisions[<rowId>]", code}`. Today the wizard shows only a generic message.

**Files:**
- Modify: `frontend/src/features/imports/lib/review-state.ts`, `components/ImportReviewStep.vue`, `components/ImportReviewRow.vue`, `pages/ImportPage.vue`, both locale files
- Test: `frontend/src/features/imports/lib/review-state.spec.ts`, `ImportPage.spec.ts`

**Interfaces:**
- Produces: `rowErrorsFrom(errors: FieldError[]): Record<string, string>`. It maps `decisions[<rowId>]` to the code, skips `ROW_UNKNOWN` and every field that does not match, and keeps the first code per row.
- `ImportReviewStep` gets a prop `rowErrors: Record<string, string>` and emits `clearRowError: [rowId: string]` when that row's include or category changes. `ImportPage` owns the map, clears it on every new preview, and sets `reviewError` to `imports.errors.rowsNeedAttention`.
- `ImportReviewRow` gets a prop `error?: string` (already translated). It renders `<p :id="`row-error-${row.rowId}`" class="text-sm text-destructive">` and sets `aria-describedby` and `aria-invalid="true"` on the category select, or on the checkbox for transfers and already-imported rows.
- When errors arrive, the active tab switches to the tab of the first row with an error.
- Copy, pt-BR first and en second:
  - `imports.errors.rowsNeedAttention` is "{n} linha precisa de correção. | {n} linhas precisam de correção." / "{n} row needs fixing. | {n} rows need fixing."
  - `imports.review.rowErrors.CATEGORY_ARCHIVED` is "Esta categoria foi arquivada. Escolha outra." / "This category was archived. Choose another one."
  - `imports.review.rowErrors.CATEGORY_REQUIRED` is "Escolha uma categoria." / "Choose a category."
  - `imports.review.rowErrors.ROW_ALREADY_IMPORTED` is "Esta linha já foi importada. Desmarque-a para continuar." / "This row was already imported. Untick it to continue."
  - `imports.review.rowErrors.invalid` is "Esta categoria não pode ser usada aqui. Escolha outra." / "This category can't be used here. Choose another one." It covers every other code (`CATEGORY_KIND_MISMATCH`, `NOT_FOUND`, `CATEGORY_NOT_ALLOWED`, `NotNull`).

- [ ] **Step 1: Write the failing tests.**
  - `review-state.spec.ts`: `rowErrorsFrom` maps `[{field:'decisions[r1]',code:'CATEGORY_ARCHIVED'},{field:'decisions[r2]',code:'ROW_UNKNOWN'},{field:'bankAccountId',code:'NotNull'}]` to `{ r1: 'CATEGORY_ARCHIVED' }`.
  - `ImportPage.spec.ts` `commit_categoryArchived_marksRowAndKeepsReview`: the commit returns that error for `r1`. Assert the alert shows "1 linha precisa de correção.", the row's select has `aria-invalid="true"` and an accessible description "Esta categoria foi arquivada. Escolha outra.", the other rows keep their categories, and the page stays on the review step.
  - `commit_rowError_clearsWhenCategoryChanges`.
  - `commit_rowErrorOnDuplicate_switchesToDuplicatesTab`.
- [ ] **Step 2: Run them and watch them fail.** Run `rtk npx vitest run src/features/imports`. Expected: FAIL.
- [ ] **Step 3: Implement** as described in the Interfaces block. Keep the `ROW_UNKNOWN` branch in `runCommit` (`backToUpload`) ahead of the new branch.
- [ ] **Step 4: Run the checks.** Run `rtk npx vitest run src/features/imports` and `rtk npm run lint`. Expected: PASS.
- [ ] **Step 5: Commit** with the message `feat(imports): show commit errors on the rows that caused them`.

---

### Task 9: Import wizard accessibility

**Files:**
- Modify: `frontend/src/features/imports/components/ImportReviewStep.vue`, `ImportResultStep.vue`, `ImportReviewRow.vue`, both locale files
- Test: `ImportPage.spec.ts`

**Decisions:**
- **Review heading:** the review step starts with `<h2 ref tabindex="-1" class="text-lg font-semibold outline-none">`. The copy is "Revisar lançamentos" in pt-BR and "Review transactions" in en, under the key `imports.review.title`. The heading is focused `onMounted` of the review step. It remounts on each new preview via `previewKey`, so a re-preview after a product remap also moves focus there.
- **Result heading:** the existing `h2` in `ImportResultStep` gets `tabindex="-1"` and is focused on mount.
- **Live summary:** the included-count span in the summary gets `aria-live="polite"`, and the `missingCategory` paragraph gets `role="status"`.
- **Apply prompt:** the row's apply prompt (`role="group"`) gets `aria-live="polite"` and `aria-labelledby` pointing to its question text, so screen readers announce the offer.

- [ ] **Step 1: Write the failing tests.**
  - `review_focusesHeadingWhenShown`: after the upload, `document.activeElement` is `screen.getByRole('heading', { name: 'Revisar lançamentos' })`.
  - `result_focusesHeadingWhenShown`.
  - `review_summaryCountIsLive`: the element with the text "{n} linhas incluídas" (`imports.review.included`) has `aria-live="polite"`.
  - `review_applyPromptIsAnnouncedGroup`: `getByRole('group', { name: /Aplicar às outras/ })` exists and has `aria-live="polite"`.
- [ ] **Step 2: Run them and watch them fail.** Run `rtk npx vitest run src/features/imports/pages/ImportPage.spec.ts`. Expected: FAIL.
- [ ] **Step 3: Implement** the decisions above.
- [ ] **Step 4: Run the checks.** Run `rtk npm run test`, `rtk npm run lint` and `rtk npx playwright test e2e/import.spec.ts`. Expected: PASS.
- [ ] **Step 5: Commit** with the message `fix(imports): move focus between wizard steps and announce review changes`.

---

## After all tasks

- [ ] Update `C:\Users\Hadryan\.claude\projects\C--Workspace-duo-finance\memory\plan1-carry-forward.md`. Remove the fixed gaps and the import follow-ups, and note V6/V7 and the move of onboarding to `users.onboarded_at`.
