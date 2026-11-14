# Revolut CSV Import Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Let a couple upload a Revolut CSV statement, review rows with remembered categories and duplicate flags, import them as transactions, and undo an import.

**Architecture:** New backend module `br.dev.duofinance.imports`: a pure parser and a pure planner, wrapped by preview (read-only), commit (re-parses the same file, one DB transaction, `ON CONFLICT DO NOTHING` on a fingerprint) and batch services. Migration V5 adds `import_batches`, `category_memory` and two nullable columns on `transactions`. Frontend `features/imports` is a three-step wizard at `/transactions/import` with pure review-state logic in its own module.

**Tech Stack:** Java 25, Spring Boot 4 (MVC, Data JPA, JdbcTemplate), PostgreSQL 16, Flyway, MockMvcTester + Testcontainers; Vue 3, TanStack Query, vue-i18n, Vitest + msw, Playwright.

**Spec:** `docs/superpowers/specs/2026-11-10-revolut-csv-import-design.md`

## Global Constraints

- Migrations V1–V4 are frozen; everything goes in `V5__imports.sql`.
- Money is `long` cents, parsed with `BigDecimal` (`movePointRight(2)`, `longValueExact()`); never `double`.
- Max 5,000 data rows per file; max file 1 MB (nginx `client_max_body_size 1m` server-wide, `spring.servlet.multipart.max-file-size: 1MB`). The commit request (file + decisions) may reach 2 MB: nginx gives `= /api/v1/imports/revolut/commit` `client_max_body_size 2m`, Spring `max-request-size: 2MB` with `file-size-threshold: 2MB` (kept in memory).
- Description stored cut to 140 characters; fingerprint uses the uncut field.
- Required header columns: `Type, Product, Started Date, Completed Date, Description, Amount, Fee, Currency, State, Balance` (any order, extras ignored).
- Dates are `yyyy-MM-dd HH:mm:ss`; the transaction date is the date part of `Completed Date`, with no time-zone conversion.
- Fee row description: `"Tarifa Revolut"` (pt-BR) / `"Revolut fee"` (en), by the importing user's locale.
- Default category fallback: the system-default category of the kind ("Outros" / "Outras receitas"), only if not archived.
- All `/api/v1/imports/**` routes are financial routes (entitlement interceptor → `402 SUBSCRIPTION_INACTIVE`).
- The uploaded file is never persisted or logged.
- UI copy in pt-BR and en; vue-i18n escaping (`{'@'}`, `{'{'}`, `{'}'}`, `{'|'}`) for literal special characters.

**Plan adjustments to the spec (decided here, apply everywhere):**

1. In this codebase `VALIDATION_FAILED` is **400**, not 422. Decision errors use `400 VALIDATION_FAILED` with `errors[]` entries `{ "field": "decisions[<rowId>]", "code": "<CODE>" }`. Bank account and mapping problems reuse `TransactionReferenceValidator` codes as-is (`404 NOT_FOUND`, `409 BANK_ACCOUNT_ARCHIVED`). The new file-level codes keep the spec's 422.
2. Fingerprint input gets an occurrence index so two byte-identical rows in one file both import: `REVOLUT|<Started Date>|<Amount>|<Currency>|<Description>|<Product>|<n>`, where `n` is the 0-based count of earlier rows in the file with the same first six parts.

## Review Focus

1. Two identical purchases with the same timestamp (split bill, two coffees): both must import, and re-importing the file must still create 0 rows. Pinned in Task 3 (`fingerprint_identicalRowsGetDistinctOccurrenceIndex`) and Task 6 (`commit_identicalRowsBothImportedAndReimportCreatesNone`).
2. A Revolut file re-saved by Excel or LibreOffice (`;` separator, CRLF line endings, BOM): CRLF and BOM must parse; a `;` file must answer `422 IMPORT_FORMAT_UNSUPPORTED`, never 500. Pinned in Task 2.
3. A late-night transaction (`Completed Date 2026-03-31 23:30:00`) must land on 2026-03-31, not shift to the next day. Pinned in Task 4 (`plan_usesCompletedDateDayWithoutTimeZoneShift`).
4. The file changes between preview and commit (user picks another file, or Revolut export differs): unknown `rowId`s must answer `400 VALIDATION_FAILED` with `ROW_UNKNOWN`, and the UI must ask to redo the preview. Pinned in Task 6 (`commit_unknownRowId_returns400RowUnknown`) and Task 10 (`review_rowUnknownError_asksToReloadPreview`).
5. A file over 1 MB is rejected by nginx with an HTML 413 body: the UI must show "file too large" from the status alone. Pinned in Task 8 (`postForm_413WithHtmlBody_throwsApiError413`) and Task 10 (`upload_413_showsTooLargeMessage`).

---

### Task 1: Schema, entities and error codes

**Files:**
- Create: `backend/src/main/resources/db/migration/V5__imports.sql`
- Create: `backend/src/main/java/br/dev/duofinance/imports/model/ImportBatch.java`, `ImportSource.java`
- Modify: `backend/src/main/java/br/dev/duofinance/transaction/model/Transaction.java` (two fields + `markImported`)
- Modify: `backend/src/main/java/br/dev/duofinance/shared/error/ErrorCode.java`, `ApiException.java`
- Modify: `backend/src/main/java/br/dev/duofinance/shared/security/WebConfig.java` (add `"/api/v1/imports/**"` to `FINANCIAL_PATHS`)
- Modify: `backend/src/main/resources/application.yml` (multipart limits)
- Test: `backend/src/test/java/br/dev/duofinance/db/ImportSchemaTest.java`

**Interfaces:**
- Produces: tables `import_batches(id, account_id, source, bank_account_id, created_by, row_count, undone_at, created_at)`, `category_memory(account_id, description_key, kind, category_id, updated_at)` PK `(account_id, description_key, kind)`; `transactions.import_batch_id`, `transactions.import_fingerprint`; unique index `uq_transactions_import_fingerprint ON transactions (account_id, import_fingerprint) WHERE import_fingerprint IS NOT NULL`.
- Produces: `enum ImportSource { REVOLUT }`; JPA entity `ImportBatch(UUID accountId, ImportSource source, UUID bankAccountId, UUID createdBy, int rowCount, Instant now)` with `getId()`, `getUndoneAt()`, `markUndone(Instant)`.
- Produces: `Transaction.markImported(UUID batchId, String fingerprint)` plus getters `getImportBatchId()`, `getImportFingerprint()`.
- Produces: `ErrorCode.IMPORT_FORMAT_UNSUPPORTED(422)`, `IMPORT_ROW_INVALID(422)`, `IMPORT_TOO_MANY_ROWS(422)`, `IMPORT_ALREADY_UNDONE(409)`; `ApiException.invalidFields(List<FieldError>)` returning `VALIDATION_FAILED` with all errors.

- [ ] **Step 1: Write the failing tests** in `ImportSchemaTest` (`@IntegrationTest`, `JdbcTemplate`, existing fixtures):
  - `fingerprintIsUniquePerAccount`: two inserts with the same `(account_id, import_fingerprint)` → second throws `DuplicateKeyException`; same fingerprint on another account → succeeds; two `NULL` fingerprints on one account → succeed.
  - `deletingBatchNullsTransactionBatchId`: delete an `import_batches` row → its transactions keep existing with `import_batch_id IS NULL`.
  - `categoryMemoryCascadesWithCategory`: delete the category → its `category_memory` row is gone.
- [ ] **Step 2: Run** `./mvnw -B test -Dtest=ImportSchemaTest` → FAIL (tables missing).
- [ ] **Step 3: Write `V5__imports.sql`** with the tables, FKs (`import_batches.account_id → accounts ON DELETE CASCADE`, `bank_account_id → bank_accounts`, `created_by → users ON DELETE SET NULL`; `transactions.import_batch_id → import_batches ON DELETE SET NULL`; `category_memory.account_id → accounts ON DELETE CASCADE`, `category_id → categories ON DELETE CASCADE`), `CHECK (source IN ('REVOLUT'))`, `CHECK (kind IN ('INCOME','EXPENSE'))`, `CHECK (row_count >= 0)`, the unique partial index, and indexes `idx_import_batches_account_created (account_id, created_at DESC)`, `idx_transactions_import_batch_id`. Add the entities, enum values, `invalidFields`, the path and `spring.servlet.multipart.max-file-size: 1MB`, `max-request-size: 2MB`, `file-size-threshold: 2MB`.
- [ ] **Step 4: Run** `./mvnw -B test -Dtest='ImportSchemaTest,FinanceSchemaTest,FinanceDynamicUpdateTest'` → PASS.
- [ ] **Step 5: Commit** `feat(imports): add V5 schema, import batch entity and error codes`.

### Task 2: Revolut CSV parser

**Files:**
- Create: `backend/src/main/java/br/dev/duofinance/imports/service/RevolutCsvParser.java`, `RevolutRow.java`
- Test: `backend/src/test/java/br/dev/duofinance/imports/service/RevolutCsvParserTest.java`
- Create: `backend/src/test/resources/imports/revolut-sample.csv` (anonymised, ~15 rows covering every rule in Task 4)

**Interfaces:**
- Produces: `record RevolutRow(int lineNumber, String type, String product, LocalDateTime startedAt, String startedRaw, LocalDateTime completedAt /* null when blank */, String description, long amountCents, String amountRaw, long feeCents, String currency, String state)`.
- Produces: `static List<RevolutRow> RevolutCsvParser.parse(byte[] file)`; throws `ApiException(IMPORT_FORMAT_UNSUPPORTED)`, `ApiException(IMPORT_ROW_INVALID, "line <n>")`, `ApiException(IMPORT_TOO_MANY_ROWS)`.

- [ ] **Step 1: Write the failing tests:**
  - `parse_sampleFile_readsAllRows`: row count and one fully asserted row (`startedRaw "2026-10-03 12:04:51"`, `amountCents -1250`, `feeCents 0`, `currency "EUR"`, `state "COMPLETED"`).
  - `parse_headerInAnyOrderWithExtraColumns_works`.
  - `parse_missingColumn_throwsFormatUnsupported` (drop `Currency`).
  - `parse_semicolonSeparated_throwsFormatUnsupported` (Review Focus 2).
  - `parse_crlfAndBom_work` (Review Focus 2).
  - `parse_quotedFieldWithComma_keepsComma` (`"Amazon, Inc."`).
  - `parse_blankCompletedDate_isNull`.
  - `parse_badAmount_throwsRowInvalidWithLine` (message `line 3`).
  - `parse_5001Rows_throwsTooManyRows`; `parse_5000Rows_works`.
  - `parse_amountWithThreeDecimals_throwsRowInvalid` (`longValueExact` overflow of scale).
- [ ] **Step 2: Run** `./mvnw -B test -Dtest=RevolutCsvParserTest` → FAIL.
- [ ] **Step 3: Implement** a small RFC 4180 reader (no new dependency): strip a UTF-8 BOM, split records on `\n` with optional preceding `\r` outside quotes, `""` as escaped quote. Header lookup by exact trimmed name. Amount via `new BigDecimal(raw).movePointRight(2).longValueExact()` (catch `ArithmeticException`/`NumberFormatException` → row invalid). Blank trailing lines are ignored.
- [ ] **Step 4: Run** the test → PASS.
- [ ] **Step 5: Commit** `feat(imports): parse Revolut CSV statements`.

### Task 3: Description keys and fingerprints

**Files:**
- Create: `backend/src/main/java/br/dev/duofinance/imports/service/DescriptionKeys.java`, `RowFingerprints.java`
- Test: `backend/src/test/java/br/dev/duofinance/imports/service/DescriptionKeysTest.java`, `RowFingerprintsTest.java`

**Interfaces:**
- Consumes: `RevolutRow` (Task 2), `SecureTokens.sha256Hex(String)`.
- Produces: `static String DescriptionKeys.of(String description)` (≤ 140 chars).
- Produces: `static List<String> RowFingerprints.of(List<RevolutRow> rows)` (same order and size as input, occurrence index per plan adjustment 2) and `static String RowFingerprints.fee(String rowFingerprint)` = `sha256Hex(rowFingerprint + ":fee")`.

- [ ] **Step 1: Write the failing tests:**
  - `of_lowercasesStripsAccentsAndCollapsesSpaces`: `"  Café   São Paulo "` → `"cafe sao paulo"`.
  - `of_removesTrailingDigitsStarsAndHashes`: `"UBER *TRIP 4821"` → `"uber *trip"`; `"Lidl #1234"` → `"lidl"`.
  - `of_digitsOnly_fallsBackToCollapsedText`: `"12345"` → `"12345"`.
  - `fingerprint_sameRowPendingThenCompleted_isEqual` (rows differ only in `state`/`completedAt`).
  - `fingerprint_identicalRowsGetDistinctOccurrenceIndex` (Review Focus 1): two identical rows → two different fingerprints; the same two rows parsed again → the same two fingerprints in order.
  - `fee_differsFromRow`.
- [ ] **Step 2: Run** `./mvnw -B test -Dtest='DescriptionKeysTest,RowFingerprintsTest'` → FAIL.
- [ ] **Step 3: Implement.** Key: `Normalizer.normalize(s, NFD)`, remove `\p{M}`, `toLowerCase(Locale.ROOT)`, collapse `\s+`, trim, strip trailing `[\s\d*#]+`; empty result → the collapsed lowercase text; cut to 140. Fingerprint input uses `startedRaw`, `amountRaw`, raw `currency`, uncut `description`, `product`.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(imports): normalise descriptions and fingerprint rows`.

### Task 4: Import planner

**Files:**
- Create: `backend/src/main/java/br/dev/duofinance/imports/service/ImportPlanner.java`, `ImportPlan.java`, `PlannedRow.java`, `SkippedRow.java`, `PlanContext.java`
- Test: `backend/src/test/java/br/dev/duofinance/imports/service/ImportPlannerTest.java`

**Interfaces:**
- Consumes: `RevolutRow`, `RowFingerprints`, `DescriptionKeys`, `TransactionKind`.
- Produces:
  - `record PlanContext(String accountCurrency, Map<String, UUID /* null = ignore */> productMapping, Map<String, TransactionKind> existingFingerprints /* fingerprint → stored kind */, Map<MemoryKey, UUID> memory, Map<TransactionKind, UUID> defaultCategories, Set<DuplicateKey> manualTransactions, String feeDescription)` with `record MemoryKey(String descriptionKey, TransactionKind kind)` and `record DuplicateKey(LocalDate date, TransactionKind kind, long amountCents, UUID bankAccountId)`.
  - `enum RowStatus { NEW, ALREADY_IMPORTED, POSSIBLE_DUPLICATE }`; `enum SkipReason { PENDING, REVERTED, OTHER_CURRENCY, EXCHANGE, PRODUCT_IGNORED, ZERO_AMOUNT }`.
  - `record PlannedRow(String rowId, LocalDate date, TransactionKind kind, long amountCents, String description, String descriptionKey, UUID bankAccountId, UUID destinationBankAccountId, UUID suggestedCategoryId, RowStatus status)`.
  - `record SkippedRow(int lineNumber, LocalDate date, String description, String amountRaw, String currency, SkipReason reason)`.
  - `record ImportPlan(List<String> products, List<PlannedRow> rows, List<SkippedRow> skipped)`.
  - `static ImportPlan ImportPlanner.plan(List<RevolutRow> rows, PlanContext ctx)`.

- [ ] **Step 1: Write the failing tests** (one rule each, rows built in code):
  - `plan_completedNegative_isExpenseWithAbsoluteAmount`; `plan_completedPositive_isIncome`.
  - `plan_pending_skippedPending`; `plan_revertedAndDeclined_skippedReverted`.
  - `plan_otherCurrency_skipped`; `plan_exchange_skipped`; `plan_zeroAmount_skipped`; `plan_unmappedOrIgnoredProduct_skippedProductIgnored`.
  - `plan_feeCreatesSeparateExpense`: fee 50 → extra `EXPENSE` 50, description `ctx.feeDescription()`, `rowId = RowFingerprints.fee(parent)`, same date and bank account.
  - `plan_oppositeAmountsSameStartedAcrossMappedProducts_becomeOneTransfer`: `bankAccountId` = negative side's account, `destinationBankAccountId` = positive side's, `rowId` = negative side's fingerprint, no category.
  - `plan_transferWithoutPair_staysExpense`; `plan_oppositeAmountsSameProduct_notPaired`.
  - `plan_knownFingerprint_isAlreadyImported`; `plan_matchingManualTransaction_isPossibleDuplicate`.
  - `plan_suggestsMemoryThenDefaultThenNull`.
  - `plan_usesCompletedDateDayWithoutTimeZoneShift` (Review Focus 3).
  - `plan_cutsDescriptionTo140`.
  - `plan_listsDistinctProductsInFileOrder`.
- [ ] **Step 2: Run** `./mvnw -B test -Dtest=ImportPlannerTest` → FAIL.
- [ ] **Step 3: Implement.** Order: compute fingerprints for all rows; filter skips; pair transfers by key `(startedRaw, abs(amountCents))` taking the first unpaired opposite-sign row whose mapped bank account differs, in file order (a pair whose negative leg is stored as `TRANSFER` pairs and is `ALREADY_IMPORTED`; a pair with either leg stored as `INCOME`/`EXPENSE` is not paired); build rows and fee rows; set status (`ALREADY_IMPORTED` wins over `POSSIBLE_DUPLICATE`); suggestion only for `INCOME`/`EXPENSE` (`memory` then `defaultCategories`; callers pass only active categories).
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(imports): plan Revolut rows into transactions`.

### Task 5: Preview endpoint

**Files:**
- Create: `backend/src/main/java/br/dev/duofinance/imports/service/ImportContextLoader.java`, `ImportPreviewService.java`
- Create: `backend/src/main/java/br/dev/duofinance/imports/repository/CategoryMemoryRepository.java` (JdbcTemplate), `ImportBatchRepository.java` (JPA)
- Create: `backend/src/main/java/br/dev/duofinance/imports/dto/PreviewResponse.java`
- Create: `backend/src/main/java/br/dev/duofinance/imports/controller/ImportController.java`
- Test: `backend/src/test/java/br/dev/duofinance/imports/controller/ImportPreviewTest.java`

**Interfaces:**
- Consumes: Tasks 1–4.
- Produces: `void TransactionReferenceValidator.validateBankAccount(UUID accountId, UUID bankAccountId)` (new public method in the existing class): only the ownership (`404 NOT_FOUND`) and archival (`409 BANK_ACCOUNT_ARCHIVED`) checks, reusing its private helpers; called for `bankAccountId` and every non-null mapped account.
- Produces: `PlanContext ImportContextLoader.load(UUID accountId, UUID userId, UUID bankAccountId, Map<String, UUID> productMapping, List<RevolutRow> rows)` — account currency, mapping with `Current → bankAccountId` unless given, existing fingerprints, memory restricted to active categories, active system-default categories per kind, manual `DuplicateKey`s (`import_fingerprint IS NULL`, date between min and max completed date of the file, bank accounts in the mapping), fee description by the user's locale.
- Produces: `ImportPlan ImportPreviewService.preview(UUID accountId, UUID userId, byte[] file, UUID bankAccountId, Map<String, UUID> productMapping)`.
- Produces: `POST /api/v1/imports/revolut/preview` (multipart parts `file`, `bankAccountId` text, optional `productMapping` as JSON part) → `200 PreviewResponse { products: [{ product, bankAccountId }], rows: PlannedRow[] (without descriptionKey), skipped: { counts: Map<SkipReason, Integer>, rows: SkippedRow[] } }`.

- [ ] **Step 1: Write the failing tests** (upload `revolut-sample.csv` with `mvc.post().multipart()`):
  - `preview_returnsRowsSkippedAndProducts_andWritesNothing` (counts of `transactions`, `import_batches`, `category_memory` unchanged).
  - `preview_knownFingerprintAndManualMatch_flagged`.
  - `preview_memorySuggestion_usedOnlyWhenCategoryActive`.
  - `preview_foreignBankAccount_returns404`; `preview_archivedBankAccount_returns409`.
  - `preview_otherFormat_returns422FormatUnsupported`.
  - `preview_inactiveSubscription_returns402`: subscription set to `EXPIRED` as `FinanceEntitlementTest` does → `402 SUBSCRIPTION_INACTIVE`.
  - Add a row to `AccountIsolationTest`'s foreign-reference cases for the preview `bankAccountId`.
- [ ] **Step 2: Run** `./mvnw -B test -Dtest=ImportPreviewTest` → FAIL.
- [ ] **Step 3: Implement** the loader, the read-only service (`@Transactional(readOnly = true)`) and the controller method (`@RequestPart MultipartFile file`, `@RequestPart String bankAccountId` parsed to `UUID`, `@RequestPart(required = false) Map<String, UUID> productMapping`).
- [ ] **Step 4: Run** `./mvnw -B test -Dtest='ImportPreviewTest,AccountIsolationTest'` → PASS.
- [ ] **Step 5: Commit** `feat(imports): preview a Revolut statement`.

### Task 6: Commit endpoint

**Files:**
- Create: `backend/src/main/java/br/dev/duofinance/imports/service/ImportCommitService.java`, `ImportedTransactionWriter.java`
- Create: `backend/src/main/java/br/dev/duofinance/imports/dto/RowDecision.java`, `CommitResponse.java`
- Modify: `ImportController.java`, `CategoryMemoryRepository.java` (upsert)
- Test: `backend/src/test/java/br/dev/duofinance/imports/controller/ImportCommitTest.java`

**Interfaces:**
- Consumes: Tasks 1–5.
- Produces: `record RowDecision(String rowId, boolean include, UUID categoryId)`; `record CommitResponse(UUID batchId /* null when nothing created */, int created, int skippedDuplicates)`.
- Produces: `CommitResponse ImportCommitService.commit(UUID accountId, UUID userId, byte[] file, UUID bankAccountId, Map<String, UUID> productMapping, List<RowDecision> decisions)` (`@Transactional`).
- Produces: `int ImportedTransactionWriter.insert(UUID accountId, UUID userId, UUID batchId, PlannedRow row, UUID categoryId, Instant now)` → 1 inserted, 0 conflict (`INSERT ... ON CONFLICT (account_id, import_fingerprint) WHERE import_fingerprint IS NOT NULL DO NOTHING`).
- Produces: `void CategoryMemoryRepository.upsert(UUID accountId, String descriptionKey, TransactionKind kind, UUID categoryId, Instant now)`.
- Produces: `POST /api/v1/imports/revolut/commit` (multipart: `file`, `bankAccountId`, optional `productMapping`, `decisions` JSON part) → `201 CommitResponse`.

- [ ] **Step 1: Write the failing tests:**
  - `commit_createsBatchTransactionsAndMemory`: rows carry `import_batch_id` and `import_fingerprint`; `row_count` = `created`.
  - `commit_reimportSameFile_createsNone` → `created 0`, `batchId null`, no new batch.
  - `commit_identicalRowsBothImportedAndReimportCreatesNone` (Review Focus 1).
  - `commit_twoConcurrentCommits_noDuplicates`: two threads, same file and decisions, started together with a `CountDownLatch` → transactions with a fingerprint equal the number of included rows, and `created` of both responses sums to that number.
  - `commit_possibleDuplicateIncludedOnlyWhenChosen`.
  - `commit_valuesComeFromFile`: decisions cannot carry amounts; the stored amount equals the file's.
  - `commit_rowWithoutDecision_notImported`.
  - `commit_unknownRowId_returns400RowUnknown` (Review Focus 4): `errors[0] = { field: "decisions[<id>]", code: "ROW_UNKNOWN" }`, nothing written.
  - `commit_alreadyImportedIncluded_returns400`, code `ROW_ALREADY_IMPORTED`.
  - `commit_includedWithoutCategory_returns400`, code `CATEGORY_REQUIRED`; `commit_categoryOnTransfer_returns400`, code `CATEGORY_NOT_ALLOWED`.
  - `commit_archivedOrWrongKindOrForeignCategory_returns400`, codes `CATEGORY_ARCHIVED`, `CATEGORY_KIND_MISMATCH`, `NOT_FOUND`.
  - `commit_memoryLastChoiceWins` (two rows with the same key and different categories → memory holds the later one in file order).
- [ ] **Step 2: Run** `./mvnw -B test -Dtest=ImportCommitTest` → FAIL.
- [ ] **Step 3: Implement.** Re-parse and re-plan with a fresh context; validate every decision and collect all errors into one `ApiException.invalidFields(...)` before writing; validate each distinct `(kind, categoryId)` once through `TransactionReferenceValidator` (map its `ApiException` code into the row error); insert the batch, then rows via the writer; if `created == 0`, delete the batch row and return `batchId null`; update `row_count`; upsert memory for included `INCOME`/`EXPENSE` rows in file order.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(imports): commit reviewed Revolut rows`.

### Task 7: Batch history and undo

**Files:**
- Create: `backend/src/main/java/br/dev/duofinance/imports/service/ImportBatchService.java`, `dto/ImportBatchResponse.java`
- Modify: `ImportController.java`, `ImportBatchRepository.java`
- Test: `backend/src/test/java/br/dev/duofinance/imports/controller/ImportBatchTest.java`

**Interfaces:**
- Produces: `record ImportBatchResponse(UUID id, ImportSource source, UUID bankAccountId, UUID createdBy, String createdByName /* null = former member */, Instant createdAt, int rowCount, Instant undoneAt)`.
- Produces: `PageResponse<ImportBatchResponse> ImportBatchService.list(UUID accountId, int page, int size)`; `void ImportBatchService.undo(UUID accountId, UUID batchId)`.
- Produces: `GET /api/v1/imports?page&size` (default size 20, newest first) and `POST /api/v1/imports/{batchId}/undo` → `204`.

- [ ] **Step 1: Write the failing tests:**
  - `list_newestFirst_withCreatorName`; `list_creatorDeleted_hasNullName`.
  - `undo_deletesOnlyThatBatchIncludingEditedRows` (edit one imported transaction via `PUT /transactions/{id}` first).
  - `undo_twice_returns409AlreadyUndone`.
  - `undo_foreignBatch_returns404` (also added to `AccountIsolationTest`).
  - `undo_byPartner_works` (`testUsers.couple(...)`).
  - `undo_thenReimport_createsRowsAgain`.
  - `undo_keepsCategoryMemory`.
- [ ] **Step 2: Run** `./mvnw -B test -Dtest=ImportBatchTest` → FAIL.
- [ ] **Step 3: Implement.** Undo locks the batch row (`SELECT ... FOR UPDATE` via `@Lock(PESSIMISTIC_WRITE)`), checks `undone_at`, deletes `transactions WHERE account_id = ? AND import_batch_id = ?`, sets `undone_at`.
- [ ] **Step 4: Run** `./mvnw -B test` (whole backend suite) → PASS.
- [ ] **Step 5: Commit** `feat(imports): list import batches and undo them`.

### Task 8: Frontend API layer

**Files:**
- Modify: `frontend/src/shared/api/http.ts` (add `postForm`), `frontend/src/shared/api/http.spec.ts`
- Modify: `frontend/src/shared/api/schema.ts` (regenerated)
- Create: `frontend/src/features/imports/api/imports-api.ts`

**Interfaces:**
- Produces: `Http.postForm<T>(path: string, form: FormData): Promise<T>` — same auth, refresh-and-retry and `ApiError` mapping as `post`, no `Content-Type` header (the browser sets the boundary), body passed as-is.
- Produces in `imports-api.ts`: `usePreviewImport()` (mutation: `{ file: File, bankAccountId: string, productMapping?: Record<string, string | null> }` → `PreviewResponse`), `useCommitImport()` (adds `decisions: RowDecision[]`; on success invalidates `['transactions']`, `['dashboard']`, `['bank-accounts']`, `['budgets']`, `['imports']`), `useImportBatches(page)`, `useUndoImport()` (same invalidations). JSON parts are appended as `new Blob([JSON.stringify(x)], { type: 'application/json' })`; `bankAccountId` as a plain string part.

- [ ] **Step 1: Write the failing tests** in `http.spec.ts`: `postForm_sendsFormDataWithoutJsonContentType`, `postForm_refreshesOn401AndRetries`, `postForm_413WithHtmlBody_throwsApiError413` (Review Focus 5: msw returns `413` with `text/html`; error has `status 413`).
- [ ] **Step 2: Run** `npx vitest run src/shared/api/http.spec.ts` → FAIL.
- [ ] **Step 3: Implement** `postForm` by letting `send` accept a `FormData` body (skip JSON header and `JSON.stringify` when `body instanceof FormData`). Start the backend (`./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`) and run `npm run api:types`; write `imports-api.ts` against the generated types.
- [ ] **Step 4: Run** `npx vitest run src/shared/api` and `npx vue-tsc -b` → PASS.
- [ ] **Step 5: Commit** `feat(imports): frontend API for Revolut import`.

### Task 9: Review state logic

**Files:**
- Create: `frontend/src/features/imports/lib/review-state.ts`
- Test: `frontend/src/features/imports/lib/review-state.spec.ts`

**Interfaces:**
- Consumes: `PreviewResponse` row type from `schema.ts`.
- Produces:
  - `type ReviewRow = PlannedRow & { include: boolean; categoryId: string | null }`.
  - `initReview(rows: PlannedRow[]): ReviewRow[]` — `NEW` included with `suggestedCategoryId`; `POSSIBLE_DUPLICATE` excluded; `ALREADY_IMPORTED` excluded and locked.
  - `sameGroup(rows: ReviewRow[], rowId: string): string[]` — other rowIds with the same normalised description and kind (normalise as the backend: lowercase, no accents, collapsed spaces, trailing `[\s\d*#]+` removed).
  - `setCategory(rows, rowId, categoryId, applyToGroup: boolean): ReviewRow[]`.
  - `totals(rows): { count: number; incomeCents: number; expenseCents: number }` over included rows (transfers count, no totals).
  - `canSubmit(rows): boolean` — at least one included row and every included `INCOME`/`EXPENSE` has a category.
  - `toDecisions(rows): RowDecision[]` — included rows only (a missing decision means not imported), `categoryId` null for transfers.

- [ ] **Step 1: Write the failing tests** for each function, including `sameGroup_ignoresTrailingCardNumbers` (`"UBER *TRIP 4821"` and `"Uber *Trip 9911"` grouped), `canSubmit_falseWithIncludedRowWithoutCategory`, `toDecisions_sendsOnlyIncludedRows`.
- [ ] **Step 2: Run** `npx vitest run src/features/imports/lib` → FAIL.
- [ ] **Step 3: Implement** as pure functions returning new arrays.
- [ ] **Step 4: Run** → PASS.
- [ ] **Step 5: Commit** `feat(imports): review state for imported rows`.

### Task 10: Import wizard UI

**Files:**
- Create: `frontend/src/features/imports/pages/ImportPage.vue`, `ImportPage.spec.ts`
- Create: `frontend/src/features/imports/components/ImportUploadStep.vue`, `ImportReviewStep.vue`, `ImportResultStep.vue`, `ImportHistory.vue`
- Modify: `frontend/src/app/router.ts` (route `/transactions/import`, `meta: app`, before `/transactions`), `frontend/src/features/transactions/pages/TransactionsPage.vue` (link "Importar extrato" / "Import statement")
- Modify: `frontend/src/shared/i18n/locales/pt-BR.json`, `en.json` (`imports.*` keys)

**Interfaces:**
- Consumes: Tasks 8–9; existing `TextField`, select, dialog and money formatter components used by `TransactionForm.vue` and the bank accounts page.

- [ ] **Step 1: Write the failing tests** in `ImportPage.spec.ts` (msw handlers for preview, commit, list, undo; existing `renderRoute` helper):
  - `upload_showsReviewWithTabsAndSkippedReasons`.
  - `upload_otherProducts_showsMappingAndRepreviews`.
  - `upload_formatError_showsNotRevolutMessage`; `upload_413_showsTooLargeMessage` (Review Focus 5).
  - `review_changeCategory_offersApplyToSameDescription`.
  - `review_submitDisabledWhileIncludedRowLacksCategory`.
  - `review_possibleDuplicatesStartUnchecked`.
  - `review_rowUnknownError_asksToReloadPreview` (Review Focus 4).
  - `result_showsCreatedAndAlreadyExisting_andUndoAsksConfirmation`.
  - `history_listsBatchesWithUndoOnlyForActive`.
  - In `TransactionsPage.spec.ts`: `transactions_hasImportStatementLink`.
- [ ] **Step 2: Run** `npx vitest run src/features/imports src/features/transactions` → FAIL.
- [ ] **Step 3: Implement** the page as a three-step state machine (`upload` → `review` → `result`), keeping the `File` in component state for the commit. Copy, pt-BR (en equivalents in `en.json`): title "Importar extrato da Revolut"; help link "Como exportar da Revolut" (text: "No app da Revolut: Conta → Extrato → Excel/CSV → escolha o período → Gerar"); tabs "Novas", "Possíveis duplicatas", "Puladas"; skip reasons "Pendente", "Revertida ou recusada", "Outra moeda", "Câmbio", "Produto ignorado", "Valor zero"; apply prompt "Aplicar às outras {n} linhas iguais?"; submit "Importar {n} lançamentos"; result "{created} lançamentos importados, {skipped} já existiam"; undo confirm "Desfazer esta importação? Os lançamentos dela serão excluídos, inclusive os que você editou depois."; errors "Este arquivo não parece ser um extrato CSV da Revolut.", "O arquivo é grande demais (máximo de 1 MB).", "O arquivo mudou desde a revisão. Envie-o de novo para revisar.".
- [ ] **Step 4: Run** `npm run lint && npx vitest run && npx vue-tsc -b` → PASS.
- [ ] **Step 5: Commit** `feat(imports): Revolut import wizard and history`.

### Task 11: End-to-end test

**Files:**
- Create: `frontend/e2e/fixtures/revolut-sample.csv` (copy of the backend sample, dates within the current month)
- Create: `frontend/e2e/import.spec.ts`

- [ ] **Step 1: Write the test** `import, reimport, undo`: register via `registerViaUi`, create a bank account, open `/transactions/import`, upload the fixture (`setInputFiles`), import, see the rows on `/transactions`; import the same file again and see "0 lançamentos importados" with the already-existing count; undo the first batch from the history and see the list empty.
- [ ] **Step 2: Run** `npm run e2e -- import.spec.ts` with the backend on profiles `dev,e2e` → PASS (it fails first only if Tasks 1–10 are incomplete).
- [ ] **Step 3: Commit** `test(imports): e2e import, reimport and undo`.
