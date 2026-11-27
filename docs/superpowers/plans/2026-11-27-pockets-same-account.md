# Pockets in the Same Revolut Account: Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** a Revolut pocket move becomes only a goal movement in the statement's own bank account, never a transaction.

**Architecture:**
- Goal movements carry the import fingerprint and the import batch, so deduplication and batch undo work without a
  transaction. The link from goal movements to transactions, and the remembered pockets account, are removed.
- The planner keeps recognising and pairing pocket legs, but both legs belong to the statement's account. The commit
  records goal movements for pocket rows instead of writing transactions.

**Tech Stack:** Spring Boot 3 / Java 21, Postgres + Flyway, JdbcTemplate + JPA, `@IntegrationTest` (Testcontainers);
Vue 3 + TS, TanStack Query, vitest + MSW, vue-i18n (pt-BR/en key parity).

**Spec:** `docs/superpowers/specs/2026-11-27-pockets-same-account-design.md`

## Global Constraints

- Branch `fix/pockets-same-account` (from `develop`). Prefix shell commands with `rtk`. Never run `spotless:apply`:
  CI does not run spotless, and it reformats unrelated files.
- Windows working copies may be CRLF: before a scripted edit, normalise the file to LF (`sed -i 's/\r$//' FILE`).
  Never commit files whose only change is line endings.
- Money is EUR, in cents (`long`).
- Migrations: the latest migration is V11; this plan adds **V12** (Task 1) and **V13** (Task 2). The spec puts both
  changes in V12; they are split so each task leaves the build green.
- Exact copy (pt-BR / en):
  - `imports.pocket.noGoal`: "Sem objetivo: nada será registrado" / "No goal: nothing will be recorded"
  - `goals.movements.importedHint`: "Veio da importação: desfaça a importação para remover" /
    "From an import: undo the import to remove it"
  - `imports.pocket.route`: "Pocket {name}" / "Pocket {name}"
  - `imports.pocket.routeUnnamed`: "Pocket" / "Pocket"
- `schema.ts` is regenerated from the running backend (`npm run api:types`). CI fails when it drifts.
- Backend tests: `cd backend && ./mvnw -q verify -Dspotless.check.skip=true`. Frontend:
  `cd frontend && npx vitest run && npx eslint src e2e && npx vue-tsc --noEmit -p tsconfig.app.json`.

## Review Focus

1. A first import that had only the savings leg of a move, followed by a statement with both legs: the move shows
   `ALREADY_IMPORTED` and the commit records nothing new (Task 2, `reimport_loneLegThenBothLegs_alreadyImported`).
2. A pocket leg must never pair with a non-pocket row of the same account that has the same time and opposite amount,
   such as a refund (Task 2, `plan_pocketLegNeverPairsWithPlainRow`).
3. Interest in the savings product becomes INCOME in the statement's account, not skipped
   (Task 2, `plan_savingsInterest_incomeInStatementAccount`).
4. Undoing a batch that holds only goal movements removes them and marks the batch undone
   (Task 1, `undo_batchWithOnlyGoalMovements_removesThem`).
5. A commit whose included rows are only pocket moves without a goal creates nothing and leaves no batch
   (Task 2, `commit_onlyPocketRowsWithoutGoal_noBatch`).

---

### Task 1: Goal movements own their import link

**Files:**
- Create: `backend/src/main/resources/db/migration/V12__goal_movements_import_link.sql`
- Modify: `backend/src/main/java/br/dev/duofinance/goal/model/GoalMovement.java`,
  `goal/repository/GoalMovementRepository.java`, `goal/service/GoalMovementService.java`,
  `transaction/service/TransactionService.java`, `imports/service/ImportCommitService.java` (the `recordGoalMovement`
  call only), `imports/service/ImportBatchService.java` (comment only, if it mentions the cascade)
- Test: `goal/controller/GoalMovementControllerTest.java`, `imports/controller/ImportBatchTest.java`,
  `transaction/controller/TransactionCrudTest.java`

**Interfaces:**
- Produces:
  - `GoalMovementService.recordImported(Goal goal, UUID userId, GoalMovementType type, long amountCents,
    LocalDate date, String importFingerprint, UUID importBatchId)`;
  - `GoalMovementService.beforeBatchRemoved(UUID accountId, UUID batchId)`, which now also **deletes** the batch's
    movements (refusal and skip restoring as today);
  - `GoalMovementRepository.findByAccountIdAndImportBatchId(UUID accountId, UUID importBatchId)`;
  - the column `goal_movements.import_fingerprint`, which Task 2 reads.
- Removed: `followTransaction`, `beforeTransactionRemoved`, `GoalMovement.transactionId`, `findByTransactionId`, and
  the native `findByImportBatch`.

- [ ] **Step 1: Write V12.** Drop `goal_movements.transaction_id` (its unique index and the cascade FK go with the
  column). Add `import_fingerprint text NULL` and `import_batch_id uuid NULL REFERENCES import_batches(id) ON DELETE
  CASCADE`. Add `CREATE UNIQUE INDEX goal_movements_import_fingerprint_uq ON goal_movements (account_id,
  import_fingerprint) WHERE import_fingerprint IS NOT NULL` and a plain index on `import_batch_id`. Add a CHECK that
  both columns are null or both are set.

- [ ] **Step 2: Rewrite the failing tests**
  - `ImportBatchTest`: the tests that insert movements with `transaction_id` now insert them with `import_batch_id` =
    the batch and `import_fingerprint` = `'fp-' || id`:
    - `undo_batch_dropsLinkedGoalMovements` → asserts the movement is gone;
    - `undo_batch_wouldMakeGoalNegative_refused` → still 400 `GOAL_AMOUNT_INVALID`, batch not undone, both movements
      kept;
    - `undo_batch_restoresTheSkipItsContributionReplaced` and
      `undo_batch_keepsTheMonthOpenWhileAnotherContributionStays` → same assertions as now.
  - New `undo_batchWithOnlyGoalMovements_removesThem`: a batch row with `row_count` 1 and no transactions, plus one
    CONTRIBUTION of 25000 linked to it. Undo returns 204, the goal's movements sum is 0, and `undone_at` is set.
  - `GoalMovementControllerTest.undo_importedMovement_conflict`: the movement is linked by `import_batch_id`, not by a
    transaction. Still 409 `GOAL_MOVEMENT_IMPORTED`.
  - `GoalMovementControllerTest`: the history's `imported` flag is true for a movement with `import_batch_id`.
  - `TransactionCrudTest`: delete the tests about linked transfers, which exercise the hooks that go away
    (`update_linked*`, `delete_linked*`, and their helpers `goalWithPocket`, `linkedTransfer`, `linkedMovement` when
    nothing else uses them).

- [ ] **Step 3: Run them and see them fail.**
  `cd backend && rtk ./mvnw -q test -Dtest='ImportBatchTest,GoalMovementControllerTest' -Dspotless.check.skip=true`.
  Expected: failures and errors on the new columns and the removed `transaction_id`.

- [ ] **Step 4: Implement.**
  - **Entity:** `GoalMovement` loses `transactionId` and gains `importFingerprint` and `importBatchId`, both
    `@Nullable`, set through the constructor that `recordImported` uses.
  - **Imported flag:** `GoalMovementResponse.imported` is `importBatchId != null`.
  - **Undo:** `undo` refuses with `GOAL_MOVEMENT_IMPORTED` when `importBatchId != null`.
  - **Batch removal:** `beforeBatchRemoved` locks the account, loads `findByAccountIdAndImportBatchId`, refuses as now,
    restores skips, then deletes those movements and flushes.
  - **Transactions:** `TransactionService` no longer calls `goalMovements` (remove the field if it becomes unused).
  - **Commit call site:** `ImportCommitService.recordGoalMovement` passes `row.rowId()` and `batch.getId()` in place
    of the transaction id. Task 2 rewrites the rest of the commit.

- [ ] **Step 5: Run the backend suite.** `cd backend && rtk ./mvnw -q verify -Dspotless.check.skip=true`.
  Expected: every test passes except the pocket tests of `ImportCommitTest`, `ImportPreviewTest` and
  `ImportPlannerTest`, which Task 2 rewrites. List the failing test names in the report.

- [ ] **Step 6: Commit.** Message: `refactor(api): goal movements carry their import fingerprint and batch`.

### Task 2: Pocket moves stay in the statement's account

**Files:**
- Create: `backend/src/main/resources/db/migration/V13__drop_pockets_bank_account.sql`
- Modify: `imports/service/ImportPlanner.java`, `imports/service/PlanContext.java`,
  `imports/service/ImportContextLoader.java`, `imports/service/ImportPreviewService.java`,
  `imports/service/ImportCommitService.java`, `imports/service/SkipReason.java`,
  `imports/controller/ImportController.java`, `imports/dto/PreviewResponse.java`,
  `account/model/Account.java`
- Test: `imports/service/ImportPlannerTest.java`, `imports/controller/ImportCommitTest.java`,
  `imports/controller/ImportPreviewTest.java`

**Interfaces:**
- Consumes (Task 1): `recordImported(goal, userId, type, amountCents, date, importFingerprint, importBatchId)`, and
  the column `goal_movements.import_fingerprint`.
- Produces (for Task 3):
  - the preview drops the multipart part `pocketsBankAccountId`, and preview and commit no longer accept it;
  - `SkipReason` loses `POCKET_NO_ACCOUNT` and `POCKET_SAME_ACCOUNT`;
  - a pocket row has `kind: TRANSFER`, `bankAccountId` = the statement's account, `destinationBankAccountId: null` and
    `pocket` set;
  - `PreviewResponse.products` no longer lists `Poupanças` / `Savings`.

- [ ] **Step 1: V13.** `ALTER TABLE accounts DROP COLUMN pockets_bank_account_id;`

- [ ] **Step 2: Write the failing planner tests** (`ImportPlannerTest`). Rewrite the pocket section:
  - `pocketCtx` loses its `pocketsBankAccountId` argument;
  - the product mapping is just `Current → CURRENT`, because the savings product maps itself to the statement's
    account.

  The existing pocket tests keep their fingerprint assertions, and their account assertions become
  `bankAccountId == CURRENT` and `destinationBankAccountId == null`. New or renamed tests:
  - `pocketDeposit_bothLegs_onePocketRowInStatementAccount`: both legs of RESERVA 250.00 give one row:
    `kind TRANSFER`, `bankAccountId CURRENT`, `destinationBankAccountId null`, `amountCents 25000`, `rowId` = the
    negative leg's fingerprint, and `pocket.direction INTO`;
  - `pocketMove_sameTimeOppositeAmount_pairsOnSameAccount`: the Current leg and the `Poupanças` leg pair, and exactly
    one row is planned;
  - `plan_pocketLegNeverPairsWithPlainRow`: a `Poupanças` +250.00 pocket leg and a `Current` -250.00 card payment
    "Mercado" at the same time give a pocket row and a separate EXPENSE row;
  - `plan_savingsInterest_incomeInStatementAccount`: "Juros brutos" +0.12 in `Poupanças` gives INCOME 12 on CURRENT,
    with no skip;
  - `pocketRow_neverPossibleDuplicate`: a manual EXPENSE of 25000 on CURRENT on the same day leaves the pocket row
    `NEW`;
  - `pocketRow_fingerprintStoredByGoalMovement_alreadyImported`: `existingFingerprints` holds the row's id as
    `TRANSFER`, so the row is `ALREADY_IMPORTED`;
  - delete `POCKET_NO_ACCOUNT` / `POCKET_SAME_ACCOUNT` tests.

- [ ] **Step 3: Write the failing integration tests** (`ImportCommitTest`, `ImportPreviewTest`). Build the CSVs
  inline with the pocket helpers already in `ImportCommitTest` (`RESERVA`, `SETUP`), with goals Reserva de Emergência
  and Setup.
  - `commit_pocketDeposit_contributionOnly_noTransaction`: no `transactions` row has the move's fingerprint. A
    CONTRIBUTION of 25000 exists with `import_fingerprint` = the rowId and `import_batch_id` = the batch. The bank
    account balance is unchanged by the move.
  - `commit_onlyPocketRowsWithoutGoal_noBatch`: the response has `created 0` and `batchId null`, and no
    `import_batches` row exists for the account.
  - `reimport_loneLegThenBothLegs_alreadyImported`: commit a file with only the `Poupanças` +1000.00 leg into Reserva,
    then preview a file with both legs. The row is `ALREADY_IMPORTED`, and committing that preview with the row
    excluded leaves the goal's saved amount at 100000.
  - `commit_withdrawalCappedAtSaved_storesFingerprint`: as the existing cap test, and the WITHDRAWAL row carries the
    fingerprint.
  - `preview_productsOmitSavings`: the response's `products` has no `Poupanças`.
  - Delete the tests about the pockets account part and about the remembered pockets account.
  - The created count includes recorded goal movements: a file with one card payment and one pocket deposit with a
    goal gives `created 2`.

- [ ] **Step 4: Run and see them fail.**
  `rtk ./mvnw -q test -Dtest='ImportPlannerTest,ImportCommitTest,ImportPreviewTest' -Dspotless.check.skip=true`.

- [ ] **Step 5: Implement.**
  - **Mapping:** `ImportContextLoader.effectiveMapping(bankAccountId, productMapping, products)` maps every savings
    product to `bankAccountId`, ignoring any mapping given for it.
  - **Fingerprints:** `load` reads goal-movement fingerprints of the account
    (`SELECT import_fingerprint FROM goal_movements WHERE account_id = ? AND import_fingerprint IS NOT NULL`) into
    `existingFingerprints` as `TRANSFER`.
  - **Context:** `PlanContext` loses `pocketsBankAccountId`.
  - **Pairing:** `pairTransfers` also pairs two rows when both are pocket rows of different products, even on the same
    account. A pocket row never pairs with a non-pocket row.
  - **Pocket rows:** `planPocketLeg` and the paired branch build with `destination` null and the statement's account.
    `build` skips the possible-duplicate check when `pocket != null`.
  - **Skips:** `accountProblem` only returns `PRODUCT_IGNORED`, and the two pocket skip reasons are deleted.
  - **Products:** `PreviewResponse.of` drops savings products from `products`.
  - **Commit:**
    - pocket rows (`pocket != null`) are excluded from `toInsert`;
    - included pocket rows with a goal are walked in `APPLY_ORDER` and recorded with `rowId` and the batch;
    - `created` = transactions inserted + movements recorded, and the batch is deleted when `created == 0`.
  - **Leftovers:** remove `rememberPocketsAccount`, `rememberedPockets`, the `pocketsBankAccountId` parameters and
    multipart parts, `Account.pocketsBankAccountId`, and the `GoalRepository` lookups used only by them.

- [ ] **Step 6: Run the backend suite.** `cd backend && rtk ./mvnw -q verify -Dspotless.check.skip=true`.
  Expected: all green.

- [ ] **Step 7: Commit.** Message: `fix(api): pocket moves are goal movements in the statement's own account`.

### Task 3: The import page stops asking for a pockets account

**Files:**
- Modify: `frontend/src/shared/api/schema.ts` (regenerated), `features/imports/api/imports-api.ts`,
  `features/imports/pages/ImportPage.vue`, `features/imports/components/ImportUploadStep.vue`,
  `features/imports/components/ImportReviewRow.vue`, `features/imports/components/ImportSkippedList.vue` (if it maps
  reasons), `shared/i18n/locales/pt-BR.json`, `shared/i18n/locales/en.json`
- Test: `features/imports/pages/ImportPage.spec.ts`, `features/goals/pages/GoalsPage.spec.ts` (hint copy)

**Interfaces:**
- Consumes (Task 2): the preview and commit no longer take `pocketsBankAccountId`, and pocket rows have
  `destinationBankAccountId: null`.

- [ ] **Step 1: Regenerate `schema.ts`.** Start Postgres (`docker run -d --rm --name plan-pg -e
  POSTGRES_USER=duofinance -e POSTGRES_PASSWORD=duofinance -e POSTGRES_DB=duofinance -p 5432:5432 postgres:16`) and
  the backend (`./mvnw -q spring-boot:run -Dspring-boot.run.profiles=dev,e2e`). Run `npm run api:types`, then stop
  the backend process **and** the container.

- [ ] **Step 2: Write the failing tests** (`ImportPage.spec.ts`).
  - Delete the tests and fixtures about the pockets account (`import_noPocketsAccount_*`, `askPockets`, the remembered
    account).
  - `pocketRow` fixtures use `destinationBankAccountId: null`.
  - New `import_pocketRow_showsPocketNotRoute`: a pocket row "Setup" reads `Pocket Setup` and does not contain `→ `
    followed by an account name. A pocket row with `name: null` reads `Pocket`.
  - `import_pocketRow_noGoal_warns` expects "Sem objetivo: nada será registrado".
  - `import_upload_neverAsksForPocketsAccount`: after a preview whose rows include pocket moves, no control labelled
    "Conta dos pockets" exists, and no request part `pocketsBankAccountId` is sent.
  - `GoalsPage.spec.ts`: the imported badge's hint is "Veio da importação: desfaça a importação para remover".

- [ ] **Step 3: Run and see them fail.**
  `cd frontend && npx vitest run src/features/imports src/features/goals`.

- [ ] **Step 4: Implement.**
  - **Upload:** remove `askPockets`, `pocketsBankAccountId` and the `pockets` emit from `ImportUploadStep`, and
    `pocketsAccount` and its plumbing from `ImportPage` and `imports-api`.
  - **Review row:** shows `imports.pocket.route` (or `routeUnnamed`) where a transfer shows `transferRoute`.
  - **i18n:**
    - add the copy from Global Constraints;
    - remove the keys made unused (`imports.upload.pocketsAccount`, `imports.upload.pocketsHint`, and the copy for
      the `POCKET_NO_ACCOUNT` / `POCKET_SAME_ACCOUNT` reasons);
    - keep pt-BR / en key parity.

- [ ] **Step 5: Run the whole frontend check.**
  `cd frontend && npx vitest run && npx eslint src e2e && npx vue-tsc --noEmit -p tsconfig.app.json && npm run -s build`.
  Expected: all pass. Run `npx prettier --check` on the changed files.

- [ ] **Step 6: Commit.** Message: `fix(web): pockets need no account of their own in the import`.
