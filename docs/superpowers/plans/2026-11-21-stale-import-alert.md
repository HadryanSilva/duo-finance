# Stale Import Alert Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The dashboard warns, one line per bank account, when that account's latest imported transaction is more than 7 days old, with a link that opens the import page with the account preselected.

**Architecture:** The dashboard response gains `lastImportedDate` (nullable) on each `AccountBalance`, filled by one new aggregate query: the latest `date` of imported transactions per bank account, independent of the selected month. The frontend compares that date with the browser's local today and shows a notice above the month summary. The import page accepts `?bankAccountId=` to preselect the account.

**Tech Stack:** Spring Boot 4 / Java 25, NamedParameterJdbcTemplate, Postgres 16, Testcontainers; Vue 3, vue-i18n, TanStack Query, Vitest + MSW.

**Spec:** `docs/superpowers/specs/2026-11-21-stale-import-alert-design.md`

**Deviation from the spec (planner's call):** the spec suggests a separate `importFreshness` list in `DashboardResponse`. The plan puts `lastImportedDate` on the existing `AccountBalance` instead. That list already holds exactly the non-archived bank accounts of the account, so the "archived accounts are excluded" rule comes for free. Update the spec's "Forma de construir" line in Task 1's commit.

## Global Constraints

- Threshold: the notice shows when `today − lastImportedDate > 7` days. Exactly 7 days shows nothing.
- "Today" is the browser's local date (`todayIso()` in `frontend/src/shared/lib/dates.ts`), never the server's.
- Only imported transactions count (`transactions.import_batch_id IS NOT NULL`). For a TRANSFER, only its origin account (`bank_account_id`) counts.
- Accounts that never received an import (`lastImportedDate = null`) and archived accounts never show the notice.
- The notice shows for every selected month, has no close button, and links to `/transactions/import?bankAccountId=<id>`.
- Copy in pt-BR and en. Dates use `formatBusinessDate(iso, locale, 'short')`. The design guard (`src/test/design-usage.spec.ts`) must stay green: no hex colors, no ad-hoc text sizes, no `formatMoney(` in templates.
- Flyway: no migration needed.
- Branch `feature/stale-import-alert` (already created from `origin/develop`, spec committed). The finished branch is pushed and a PR opened against `develop`.
- Local checks: run Spotless only on touched files (`-DspotlessFiles=`), and check formatting with `prettier --check --end-of-line auto` on touched files (CRLF checkout).

## Review Focus

1. **Past month selected:** viewing an old month must still use the real latest import, not the latest one before that month's end. The query must not reuse the balance query's `date <= asOf` filter. Covered by `dashboard_lastImportedDate_ignoresSelectedMonth` (Task 1).
2. **Manual transactions after an old import:** a manual expense dated today in an account whose last import is 20 days old must not hide the notice. Covered by `dashboard_lastImportedDate_ignoresManualTransactions` (Task 1).
3. **Transfer between two imported accounts:** an imported TRANSFER counts only for the origin account. Covered by `dashboard_lastImportedDate_transferCountsForOriginOnly` (Task 1).
4. **Imported transaction dated in the future** (for example a scheduled row): `daysSince` is negative, so no notice. Covered by `staleImports_futureDate_isNotStale` (Task 2).
5. **Unknown or archived `bankAccountId` in the import URL** (an old link, an account archived since): the page must fall back to no preselection instead of previewing against a bad id. Covered by `upload_queryBankAccountUnknown_leavesSelectionEmpty` (Task 3).

---

### Task 1: Backend — last imported date per bank account

**Files:**
- Create: `backend/src/main/java/br/dev/duofinance/dashboard/repository/LastImportedDateQuery.java`
- Modify: `backend/src/main/java/br/dev/duofinance/dashboard/dto/DashboardResponse.java` (`AccountBalance`)
- Modify: `backend/src/main/java/br/dev/duofinance/dashboard/service/DashboardService.java`
- Modify: `frontend/src/shared/api/schema.ts` (regenerated)
- Modify: `docs/superpowers/specs/2026-11-21-stale-import-alert-design.md` ("Forma de construir", see the deviation above)
- Test: `backend/src/test/java/br/dev/duofinance/dashboard/controller/DashboardControllerTest.java`

**Interfaces:**
- Produces: JSON `accounts[].lastImportedDate`: `"YYYY-MM-DD"` or `null`. In `schema.ts`: `AccountBalance.lastImportedDate: string | null` (with `/** Format: date */`).
- Produces (Java): `public record AccountBalance(UUID id, String name, BankAccountType type, long balanceCents, @Nullable LocalDate lastImportedDate)`.
- Produces (Java): `LastImportedDateQuery#lastImportedDates(UUID accountId): Map<UUID, LocalDate>`. It only has entries for bank accounts with at least one imported transaction.

- [ ] **Step 1: Write the failing tests** in `DashboardControllerTest`

Add a helper that marks transactions as imported, without going through the CSV flow:

```java
/** Inserts a REVOLUT batch and attaches the given bank account's transactions dated {@code dates} to it. */
private void markImported(TestSession s, UUID bank, String... dates)
```

The helper inserts into `import_batches` (`id`, `account_id`, `source = 'REVOLUT'`, `bank_account_id`, `created_by = NULL`, `row_count`, `created_at = now()`). It then runs `UPDATE transactions SET import_batch_id = ? WHERE account_id = ? AND bank_account_id = ? AND date = ANY(?)`. Create the transactions with the existing `tx` / `transfer` helpers first. The clock is `2027-03-15`.

```java
@Test
void dashboard_lastImportedDate_isLatestImportedPerAccount() {
    // checking: imported 2027-03-01 and 2027-03-05; manual-only account; account never touched
    // expect checking.lastImportedDate == "2027-03-05"; the other two are JSON null
}

@Test
void dashboard_lastImportedDate_ignoresManualTransactions() {
    // checking: imported 2027-02-20; manual EXPENSE 2027-03-15 (not marked)
    // expect "2027-02-20"
}

@Test
void dashboard_lastImportedDate_ignoresSelectedMonth() {
    // checking: imported 2027-03-10; request ?month=2027-01
    // expect "2027-03-10" (not null, not limited by balancesAsOf)
}

@Test
void dashboard_lastImportedDate_transferCountsForOriginOnly() {
    // imported TRANSFER 2027-03-12 from checking to savings (mark by checking's bank_account_id)
    // expect checking "2027-03-12", savings null
}
```

Assert through `d.path("accounts")`, finding each account by `name`, and use `isNull()` on `path("lastImportedDate")` for the null cases. Also update `dashboard_queryCountIsFixedAndIndependentOfData`: the dashboard now takes **9** statements. Change `+ 8` to `+ 9`, add "last imported dates" to the comment's list, and call `markImported` for each bank account inside `seedVolume`, so the count is checked with imported data too.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `cd backend && ./mvnw test -Dtest=DashboardControllerTest` (Docker must be running.)
Expected: the four new tests FAIL because `lastImportedDate` is missing (the node is missing, not null). The query-count test FAILs with 8 ≠ 9 once seedVolume calls `markImported`, or it may still pass before the query exists. Either way, it must pass after Step 3.

- [ ] **Step 3: Implement**

- `LastImportedDateQuery` is a `@Repository` in the same style as `BankAccountBalanceQuery`. Its SQL:

```sql
SELECT t.bank_account_id AS id, MAX(t.date) AS last_date
FROM transactions t
WHERE t.account_id = :accountId
  AND t.import_batch_id IS NOT NULL
  AND t.bank_account_id IS NOT NULL
GROUP BY t.bank_account_id
```

- `DashboardService`: inject the query, call it once in `dashboard(...)`, and pass `lastImported.get(b.getId())` into `AccountBalance`. Update the class Javadoc's statement list.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `cd backend && ./mvnw test -Dtest=DashboardControllerTest`
Expected: all PASS.

- [ ] **Step 5: Regenerate the API types**

Start the backend against a throwaway Postgres, not the dev volume on :5432. Then run `cd frontend && npm run api:types`. The expected diff is only this, inside `AccountBalance`:

```ts
            /** Format: date */
            lastImportedDate: string | null;
```

If the backend can't be run locally, add exactly those lines by hand. The CI `api-types` job verifies them. Then add `lastImportedDate: null` to every `AccountBalance` literal in frontend specs, so `vue-tsc -b` stays green. Find them with `grep -rn "balanceCents:" frontend/src --include=*.spec.ts`.

- [ ] **Step 6: Verify the backend suite and the frontend type-check**

Run: `cd backend && ./mvnw verify -DspotlessFiles=<touched java files>` and `cd frontend && npx vue-tsc -b`
Expected: both succeed.

- [ ] **Step 7: Commit**

```bash
rtk git add backend frontend/src docs/superpowers/specs/2026-11-21-stale-import-alert-design.md
rtk git commit -m "feat(api): dashboard accounts carry their last imported date"
```

---

### Task 2: Frontend — stale import notice on the dashboard

**Files:**
- Modify: `frontend/src/features/dashboard/lib/figures.ts`
- Test: `frontend/src/features/dashboard/lib/figures.spec.ts`
- Create: `frontend/src/features/dashboard/components/StaleImportsNotice.vue`
- Create: `frontend/src/features/dashboard/components/StaleImportsNotice.spec.ts`
- Modify: `frontend/src/features/dashboard/pages/DashboardPage.vue`
- Modify: `frontend/src/features/dashboard/pages/DashboardPage.spec.ts`
- Modify: `frontend/src/shared/i18n/locales/pt-BR.json`, `frontend/src/shared/i18n/locales/en.json`

**Interfaces:**
- Consumes: `DashboardResponse['accounts'][number].lastImportedDate: string | null` (Task 1).
- Produces: `export const STALE_IMPORT_DAYS = 7` and `export function staleImports(accounts: DashboardResponse['accounts'], today: string): StaleImport[]` in `figures.ts`, where `type StaleImport = { id: string; name: string; lastImportedDate: string; days: number }`. Task 3 relies on the link format `/transactions/import?bankAccountId=<id>`.

- [ ] **Step 1: Write the failing unit tests** in `figures.spec.ts`

```ts
describe('staleImports', () => {
  const acc = (id: string, lastImportedDate: string | null) =>
    ({ id, name: id, type: 'CHECKING', balanceCents: 0, lastImportedDate }) as const
  it('moreThan7Days_isStale', () =>
    expect(staleImports([acc('a', '2026-11-13')], '2026-11-21')).toEqual([
      { id: 'a', name: 'a', lastImportedDate: '2026-11-13', days: 8 },
    ]))
  it('exactly7Days_isNotStale', () =>
    expect(staleImports([acc('a', '2026-11-14')], '2026-11-21')).toEqual([]))
  it('neverImported_isIgnored', () => expect(staleImports([acc('a', null)], '2026-11-21')).toEqual([]))
  it('futureDate_isNotStale', () =>
    expect(staleImports([acc('a', '2026-11-30')], '2026-11-21')).toEqual([]))
  it('acrossMonthAndDst_countsCalendarDays', () =>
    expect(staleImports([acc('a', '2026-10-20')], '2026-11-21')[0]?.days).toBe(32))
  it('keepsAccountOrder', () =>
    expect(
      staleImports([acc('b', '2026-11-01'), acc('a', '2026-11-02')], '2026-11-21').map((s) => s.id),
    ).toEqual(['b', 'a']))
})
```

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/features/dashboard/lib/figures.spec.ts`
Expected: FAIL because `staleImports` is not exported.

- [ ] **Step 3: Implement `staleImports`** in `figures.ts`

Count calendar days from the date parts with `Date.UTC`, never with `new Date('YYYY-MM-DD')`, so the result doesn't depend on the time zone or DST. Keep the accounts' order.

- [ ] **Step 4: Run them to verify they pass**

Run: `cd frontend && npx vitest run src/features/dashboard/lib/figures.spec.ts`
Expected: PASS.

- [ ] **Step 5: Write the failing component and page tests**

`StaleImportsNotice.spec.ts` (props `{ items: StaleImport[] }`):
- `rendersOneLinePerAccount_withLink`: two items give two list items. pt-BR text: `` `Revolut: lançamentos até ${formatBusinessDate('2026-11-03', 'pt-BR')} (há 18 dias).` ``. Each item has a link named `"Importar extrato"` with `href` `/transactions/import?bankAccountId=a1`.
- `empty_rendersNothing`: `items: []` renders no `role="status"` element.
- `hasNoCloseButton`: `queryByRole('button')` is null.

In `DashboardPage.spec.ts`, add `lastImportedDate` to the `full` fixture (`null` for both accounts) and add:
- `staleImport_showsNoticeAboveSummary`: one account's `lastImportedDate` is `addDays(todayIso(), -10)`. Build it in the spec from local date parts, the same way `todayIso` does. The notice shows the account's name and appears before the month summary in document order.
- `staleImport_showsInPastMonth`: same data on route `/dashboard?month=<addMonths(currentMonth(), -2)>`, and the notice is present.

- [ ] **Step 6: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/features/dashboard`
Expected: the new tests FAIL.

- [ ] **Step 7: Implement**

- i18n, under `dashboard.staleImports`:
  - pt-BR: `"line": "{account}: lançamentos até {date} (há {days} dias)."`, `"action": "Importar extrato"`
  - en: `"line": "{account}: transactions up to {date} ({days} days ago)."`, `"action": "Import statement"`
- `StaleImportsNotice.vue` is a `role="status"` container (not `alert`: it's a reminder, not an error) with one row per item. Each row has `TriangleAlertIcon` from `@lucide/vue` in `text-warning`, the text, and a `RouterLink` styled like the link in `ManageNotice.vue`. It uses the card border/background like `ManageNotice` and renders nothing when `items` is empty.
- `DashboardPage.vue` computes `staleImports(dashboard.data.value?.accounts ?? [], todayIso())` and renders `<StaleImportsNotice>` as the first child inside the `v-if="!dashboard.isError.value"` template, before `MonthSummaryCard`.

- [ ] **Step 8: Run the dashboard tests and the design guard**

Run: `cd frontend && npx vitest run src/features/dashboard src/test/design-usage.spec.ts src/shared/i18n`
Expected: PASS.

- [ ] **Step 9: Commit**

```bash
rtk git add frontend/src
rtk git commit -m "feat(web): dashboard warns when an account's imports are over a week old"
```

---

### Task 3: Frontend — import page preselects the account from the URL

**Files:**
- Modify: `frontend/src/features/imports/pages/ImportPage.vue`
- Test: `frontend/src/features/imports/pages/ImportPage.spec.ts`

**Interfaces:**
- Consumes: the link `/transactions/import?bankAccountId=<id>` (Task 2).

- [ ] **Step 1: Write the failing tests** in `ImportPage.spec.ts`, using the bank accounts the spec's existing handlers already serve:
- `upload_queryBankAccount_preselectsIt`: route `/transactions/import?bankAccountId=<an active account id>`, and the account select shows that account.
- `upload_queryBankAccountUnknown_leavesSelectionEmpty`: route with `?bankAccountId=nope`, and also with an archived account's id. The select keeps its empty placeholder, and no preview request is sent.

- [ ] **Step 2: Run them to verify they fail**

Run: `cd frontend && npx vitest run src/features/imports/pages/ImportPage.spec.ts`
Expected: the preselect test FAILs. The unknown-id test may already pass.

- [ ] **Step 3: Implement**

In `ImportPage.vue`, read `useRoute().query.bankAccountId`. Once `accountOptions` loads, and only while `bankAccountId` is still `''`, set it when the query value is a string matching an option's `id` (`accountOptions` already excludes archived accounts). Do it once: a later choice by the user must not be overwritten.

- [ ] **Step 4: Run the imports tests**

Run: `cd frontend && npx vitest run src/features/imports`
Expected: PASS.

- [ ] **Step 5: Full frontend check**

Run: `cd frontend && npx vitest run && npx vue-tsc -b && npx eslint . && npx prettier --check --end-of-line auto <touched files>`
Expected: all succeed.

- [ ] **Step 6: Commit**

```bash
rtk git add frontend/src
rtk git commit -m "feat(web): import page preselects the account from the link"
```
