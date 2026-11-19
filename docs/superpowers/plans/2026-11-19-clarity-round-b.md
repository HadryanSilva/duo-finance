# Clarity round B Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Give the other pages the context their numbers lack:
- Lançamentos: period totals from a new endpoint;
- Orçamentos: a month summary;
- Contas: the total balance;
- Recorrências: the fixed monthly commitment;
- Objetivos: a total line.

**Architecture:**
- **Backend:** one read endpoint, `GET /api/v1/transactions/totals`, in the transaction module. It shares the list's filter predicates through one spec-building method, and sums with a single aggregate query.
- **Frontend:** pure functions in `shared/lib/figures.ts`. Each page gets a small summary component that renders them.

**Tech Stack:** Spring Boot 4 / Java 25 / JPA Criteria, Postgres 16, MockMvcTester integration tests; Vue 3, vue-i18n, TanStack Vue Query, Vitest + Testing Library + MSW.

**Spec:** `docs/superpowers/specs/2026-11-19-clarity-round-b-design.md`

## Global Constraints

- Branch `feature/clarity-round-b`, stacked on `feature/dashboard-clarity` (PR #18). The PR's base is `feature/dashboard-clarity` until #18 merges, then `develop`.
- No new colors, fonts or tokens. The design guard must stay green: no `formatMoney` in feature views (money in plain strings goes through a lib helper), no cross-feature imports, no hex, no arbitrary sizes.
- All copy goes in `pt-BR.json` and `en.json`, with plural forms (`a | b`) wherever a count appears. `i18n.spec.ts` checks parity.
- `frontend/src/shared/api/schema.ts` is generated (`npm run api:types` with the backend `dev` profile running) and is never edited by hand. New types are re-exported in `shared/api/types.ts`.
- Transfers:
  - without `bankAccountId` they never add to income or expense;
  - with `bankAccountId` = X, a transfer **into** X is income and one **out of** X is expense.
- Monthly equivalent: MONTHLY × 1, WEEKLY × 52 ÷ 12, YEARLY ÷ 12, rounded to cents once (`Math.round`).
- Commands:
  - backend: `cd backend && ./mvnw -q test -Dtest=<Class>` (use `mvnw.cmd` on Windows), plus `./mvnw -q spotless:apply` before committing;
  - frontend: from `frontend/`, `npx vitest run <path>`; at the end, `npm run lint` and `npm run build`.

## Review Focus

1. **Totals and list agree** for the same filter, including the `q` search combined with a bank-account filter. Pinned in Task 1 (`totals_matchListForSameFilter`).
2. **A transfer between two own accounts:**
   - filtered on the origin, it is expense;
   - filtered on the destination, it is income;
   - unfiltered, it is neither.
   Pinned in Task 1.
3. **Archived accounts never count in Contas' total, even with "Mostrar arquivadas" on.** Pinned in Task 6.
4. **Recorrências with only transfer, paused or finished rules:** no summary, never "€ 0,00 / € 0,00". Pinned in Task 7.
5. **The totals request fails while the list loads:** the list stays usable and the strip shows the error inline. Pinned in Task 4.

---

### Task 1: Backend: `GET /transactions/totals`

**Files:**
- Create: `backend/src/main/java/br/dev/duofinance/transaction/dto/TransactionTotalsResponse.java`
- Modify: `backend/src/main/java/br/dev/duofinance/transaction/service/TransactionQueryService.java`, `backend/src/main/java/br/dev/duofinance/transaction/controller/TransactionController.java`
- Test: `backend/src/test/java/br/dev/duofinance/transaction/controller/TransactionTotalsTest.java`

**Interfaces:**
- Produces:
  - `record TransactionTotalsResponse(long incomeCents, long expenseCents, long netCents, long count)`;
  - `TransactionQueryService.totals(UUID accountId, TransactionFilter filter): TransactionTotalsResponse`;
  - endpoint `GET /api/v1/transactions/totals`, taking the list's query params except `page` and `size`.
- In `TransactionQueryService`, extract `private Specification<Transaction> specFor(UUID accountId, TransactionFilter filter)` out of `list`. It holds the validation (`from: OutOfRange`, `q: Size`), the ownership checks and every predicate. `list` and `totals` both call it.
- Sum in one `CriteriaQuery<Tuple>`:
  - `count(root)`;
  - `sum(CASE WHEN kind=INCOME … )` and `sum(CASE WHEN kind=EXPENSE … )`;
  - with `bankAccountId`, also `sum(CASE WHEN kind=TRANSFER AND destinationBankAccountId=X …)` added to income, and `sum(CASE WHEN kind=TRANSFER AND bankAccountId=X …)` added to expense;
  - the `where` comes from `specFor(...).toPredicate(root, query, cb)`, and null sums become 0.
- Declare the totals mapping **before** `/{id}`, or rely on Spring's literal-over-pattern precedence. A test pins it.

- [ ] **Step 1: Write the failing tests** in `TransactionTotalsTest`. Copy the `@IntegrationTest` setup, `get` and `body` helpers from `TransactionListTest`. Use `fixtures.bankAccount(s, name, 0)`, and build transfers as `FinanceFixtures` does at line ~164 (`kind TRANSFER`, `bankAccountId`, `destinationBankAccountId`).
  - `totals_sumIncomeAndExpense_ignoringTransfers`: income 5000, expenses 1200 and 300, and a transfer of 999 between two accounts. Expect `{incomeCents:5000, expenseCents:1500, netCents:3500, count:4}`.
  - `totals_accountFilter_countsTransferInAsIncomeAndOutAsExpense`: accounts A and B, an expense of 100 on A, and a transfer A→B of 999.
    - `?bankAccountId=A` gives `{income:0, expense:1099, net:-1099, count:2}`;
    - `?bankAccountId=B` gives `{income:999, expense:0, net:999, count:1}`.
  - `totals_matchListForSameFilter`: seed 60 mixed rows. For `?from=2026-11-01&to=2026-11-30&q=fei&bankAccountId=A`, `count` equals the list's `totalElements` for the same query.
  - `totals_kindTransferWithoutAccount_onlyCounts`: income and expense are 0, and count equals the number of transfers.
  - `totals_otherAccountsData_isNotSummed`: Bob's rows are absent from Ana's totals. `?bankAccountId=<Ana's bank>` as Bob gives 404, and `?categoryId=<Ana's>` as Bob gives 404.
  - `totals_fromAfterTo_isOutOfRange`: 400, with `$.errors[0].code` = `OutOfRange`.
  - `totals_emptyPeriod_isZeros`: `{0,0,0,0}`.
  - `totals_pathIsNotTakenAsAnId`: `GET /transactions/totals` answers 200 with the totals shape, not the detail endpoint's error.
- [ ] **Step 2: Run** `cd backend && ./mvnw -q test -Dtest=TransactionTotalsTest`. Expected: FAIL (404 or compile error).
- [ ] **Step 3: Implement** the record, `specFor`, `totals` and the controller method. `list` behavior must not change.
- [ ] **Step 4: Run** `./mvnw -q test -Dtest='TransactionTotalsTest,TransactionListTest'`. Expected: PASS.
- [ ] **Step 5: Run** `./mvnw -q spotless:apply`, then commit `feat(api): transaction totals for the list's filters`.

---

### Task 2: API types and the `useTransactionTotals` query

**Files:**
- Modify: `frontend/src/shared/api/schema.ts` (regenerated), `frontend/src/shared/api/types.ts`, `frontend/src/shared/api/queries/transactions.ts`
- Test: `frontend/src/shared/api/queries/transactions-totals.spec.ts`

**Interfaces:**
- Consumes: Task 1's endpoint.
- Produces:
  - `export type TransactionTotalsResponse = Schemas['TransactionTotalsResponse']`, in `types.ts`;
  - `useTransactionTotals(filter: MaybeRefOrGetter<TransactionFilter>)`, a `useQuery` with key `['transactions', 'totals', filter]`, so the existing `['transactions']` invalidation refreshes it. It calls `GET /transactions/totals` with the filter as query params.

- [ ] **Step 1: Regenerate the types.** Start the backend with the `dev` profile (`docker compose -f infra/docker-compose.dev.yml up -d`, then `./mvnw spring-boot:run -Dspring-boot.run.profiles=dev`). In `frontend`, run `npm run api:types`. Expected: the `schema.ts` diff adds `TransactionTotalsResponse` and the `/api/v1/transactions/totals` path only.
- [ ] **Step 2: Write the failing test** `totals_requestsWithFilterAndRefetchesOnInvalidate`. Mount a tiny component with `render` that uses the hook with `{ from: '2026-11-01', to: '2026-11-30', categoryId: 'c1' }`. MSW asserts the query string. Then `queryClient.invalidateQueries({ queryKey: ['transactions'] })` triggers a second request.
- [ ] **Step 3: Run** `npx vitest run src/shared/api/queries/transactions-totals.spec.ts`. Expected: FAIL (hook missing).
- [ ] **Step 4: Implement** the hook, and the re-export in `types.ts`.
- [ ] **Step 5: Run** the same command. Expected: PASS. Commit `feat(web): query for transaction totals`.

---

### Task 3: Shared figures

**Files:**
- Create: `frontend/src/shared/lib/figures.ts`, `frontend/src/shared/lib/figures.spec.ts`
- Modify: `frontend/src/features/dashboard/lib/figures.ts` and `figures.spec.ts` (remove the two moved functions and their tests), `frontend/src/features/dashboard/components/BudgetsCard.vue` (import path)

**Interfaces:**
- Produces, in `@/shared/lib/figures`:
  - moved unchanged from the dashboard: `daysLeftInMonth(month: string, today: string): number | null` and `budgetRemainder(spentCents: number, limitCents: number): { over: boolean; amountCents: number }`;
  - `monthlyEquivalentCents(amountCents: number, frequency: 'WEEKLY' | 'MONTHLY' | 'YEARLY'): number`, rounded to whole cents;
  - `fixedMonthly(rules: { kind: TransactionKind; amountCents: number; frequency: Frequency }[]): { inCents: number; outCents: number; netCents: number }`. It sums the unrounded monthly equivalents per kind, ignores TRANSFER, and rounds each total once.

- [ ] **Step 1: Write the failing tests** in `shared/lib/figures.spec.ts`. Move the existing `daysLeftInMonth` and `budgetRemainder` cases there, then add:
  - `monthlyEquivalent_weekly`: `monthlyEquivalentCents(4000, 'WEEKLY')` is `17333`;
  - `monthlyEquivalent_yearly`: `monthlyEquivalentCents(36000, 'YEARLY')` is `3000`;
  - `monthlyEquivalent_monthly`: 95000 stays `95000`;
  - `fixedMonthly_sumsByKindIgnoringTransfers`: income 320000 MONTHLY, expense 95000 MONTHLY, expense 4000 WEEKLY and a TRANSFER 50000 MONTHLY give `{ inCents: 320000, outCents: 112333, netCents: 207667 }`;
  - `fixedMonthly_roundsOnce`: three WEEKLY expenses of 1 cent give `outCents` `13`, not `12` (52·3/12 = 13).
- [ ] **Step 2: Run** `npx vitest run src/shared/lib/figures.spec.ts`. Expected: FAIL.
- [ ] **Step 3: Implement** the new functions, move the two others, and update the dashboard imports.
- [ ] **Step 4: Run** `npx vitest run src/shared/lib src/features/dashboard src/test`. Expected: PASS, with the design guard included. Commit `refactor(web): shared figures for budgets and recurring rules`.

---

### Task 4: Lançamentos totals strip, and the day net under an account filter

**Files:**
- Create: `frontend/src/features/transactions/components/TransactionTotals.vue`, `TransactionTotals.spec.ts`
- Modify: `frontend/src/features/transactions/pages/TransactionsPage.vue`, `components/TransactionDayGroup.vue`, `pages/TransactionsPage.spec.ts`, both locales

**Interfaces:**
- Consumes: `useTransactionTotals` (Task 2).
- `<TransactionTotals :filter="TransactionFilter" />`:
  - it owns the query;
  - while it loads, it shows three `Skeleton`s;
  - on error, it shows `<p role="alert">` with `errorMessage(...)`;
  - when `count === 0`, it renders nothing;
  - with `filter.kind === 'TRANSFER' && !filter.bankAccountId`, it shows only the count line;
  - otherwise it shows a `dl` with `aria-label` `transactions.totals.label`.
- The `dl` holds:
  - Entrou: `MoneyText kind="INCOME"`;
  - Saiu: `MoneyText kind="EXPENSE"`;
  - Saldo do período: `MoneyText signed`, with the class `text-expense` when `netCents < 0`;
  - and the count line.
- i18n under `transactions.totals`:
  - `label`: "Totais do período" / "Period totals";
  - `in`: "Entrou" / "In";
  - `out`: "Saiu" / "Out";
  - `net`: "Saldo do período" / "Period balance";
  - `count`: "{n} lançamento | {n} lançamentos" / "{n} transaction | {n} transactions";
  - `transfers`: "{n} transferência | {n} transferências" / "{n} transfer | {n} transfers".
- `TransactionDayGroup` gets a new prop `bankAccountId?: string`. With it, a TRANSFER row adds `+amount` when `destinationBankAccount?.id === bankAccountId`, or `-amount` when the origin `bankAccount?.id === bankAccountId`. Check the field names on `TransactionResponse`. The page passes `filter.bankAccountId`, and places the strip between `<TransactionFilters>` and the list.

- [ ] **Step 1: Write failing tests:**
  - in `TransactionTotals.spec.ts`, MSW on `/transactions/totals`:
    - `totals_showInOutAndNet`: `{income 0, expense 98000, net -98000, count 14}` shows `€ 980,00`, `14 lançamentos`, and the net element has `text-expense`;
    - `totals_transferKindWithoutAccount_showsOnlyCount`: `kind TRANSFER` with count 3 shows `3 transferências` and no `Entrou`;
    - `totals_withAccount_transferKind_showsAmounts`: `kind TRANSFER` with a `bankAccountId` shows `Entrou`;
    - `totals_error_showsAlert`: a 500 gives `role=alert`;
    - `totals_zeroCount_rendersNothing`;
  - in `TransactionsPage.spec.ts`:
    - `totals_errorKeepsListUsable`: totals answer 500 while the list answers rows. The rows are visible and the alert is shown;
    - `dayNet_accountFilter_countsTransfers`: with `?bankAccountId=a1` and a day holding a transfer of 999 from a2 to a1, `day-net-<date>` shows `+€ 9,99`. Without the filter it shows `€ 0,00`.
- [ ] **Step 2: Run** `npx vitest run src/features/transactions`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS. Commit `feat(web): transactions show the totals of the active filter`.

---

### Task 5: Orçamentos month summary and row remainders

**Files:**
- Create: `frontend/src/features/budgets/components/BudgetsSummary.vue`, `BudgetsSummary.spec.ts`
- Modify: `frontend/src/features/budgets/pages/BudgetsPage.vue`, both locales

**Interfaces:**
- Consumes: `budgetRemainder` and `daysLeftInMonth` from `@/shared/lib/figures`, and `todayIso`.
- `<BudgetsSummary :budgets="BudgetResponse[]" :month="string" />`. It is rendered by the page only when `rows.length`, between the month selector and the list.
  - It shows `budgets.summary.spentOf` with `MoneyText` slots: "{spent} gastos de {limit} orçados" / "{spent} spent of {limit} budgeted".
  - One `BudgetProgressBar` with `percent = Math.round(spent*100/limit)`. Its `status` uses the same thresholds the backend uses for each budget: read `BudgetStatus.java` for the warning percent and mirror it in one constant with a comment pointing there.
  - "restam {amount} no total" / "{amount} left in total", or "passou {amount} no total" / "{amount} over in total".
  - `exceeded`: "{n} estourado | {n} estourados" / "{n} over budget", when n > 0.
  - Days left as on the dashboard (`dashboard.budgets.daysLeft` and `lastDay` are reused through new `budgets.summary.daysLeft` and `lastDay` keys with the same text).
- Each row in `BudgetsPage` adds, next to the name, the dashboard's "restam/passou" text, with the colors: `text-expense` if over or `EXCEEDED`, `text-warning` if `WARNING`, otherwise `text-foreground`. New keys `budgets.remaining` and `budgets.over` carry the same texts as `dashboard.budgets.*`.

- [ ] **Step 1: Write failing tests:**
  - in `BudgetsSummary.spec.ts`, fake time 2026-11-20:
    - `summary_spentOfBudgeted`: budgets 98000/100000 and 36750/30000 give `€ 1.347,50 gastos de € 1.300,00 orçados`, `passou € 47,50 no total` and `1 estourado`;
    - `summary_under_saysRemaining`: `restam` text, and no `estourado`;
    - `summary_currentMonth_daysLeft`: `faltam 11 dias`;
    - `summary_pastMonth_noDays`;
  - in `BudgetsPage.spec.ts`: `row_showsRemaining`. The Mercado row contains `restam €`.
- [ ] **Step 2: Run** `npx vitest run src/features/budgets`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS. Commit `feat(web): budgets page sums the month and says what is left`.

---

### Task 6: Contas total

**Files:**
- Modify: `frontend/src/features/bank-accounts/pages/BankAccountsPage.vue`, `BankAccountsPage.spec.ts`, both locales

**Interfaces:**
- The total sits above the list: a `div` with the bordered card look already used on this page. It holds `bankAccounts.total.label` ("Saldo total" / "Total balance"), `MoneyText` with the sum of `balanceCents` over the non-archived accounts, and `bankAccounts.total.count` ("em {n} conta ativa | em {n} contas ativas" / "across {n} active account | across {n} active accounts").
- It shows only when there is at least one active account.

- [ ] **Step 1: Write failing tests:**
  - `total_sumsActiveAccounts`: accounts 592000 and 200000 give `€ 7.920,00` and `em 2 contas ativas`;
  - `total_ignoresArchived_evenWhenShown`: route `?archived=1` with an archived account of 50000. The total is unchanged and the count says 2.
- [ ] **Step 2: Run** `npx vitest run src/features/bank-accounts`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS. Commit `feat(web): accounts page shows the total balance`.

---

### Task 7: Recorrências fixed monthly summary and monthly equivalents

**Files:**
- Create: `frontend/src/features/recurring/components/RecurringSummary.vue`, `RecurringSummary.spec.ts`
- Modify: `frontend/src/features/recurring/pages/RecurringPage.vue`, `components/RecurringRuleRow.vue`, `pages/RecurringPage.spec.ts`, both locales

**Interfaces:**
- Consumes: `fixedMonthly` and `monthlyEquivalentCents` (Task 3).
- `<RecurringSummary :rules="RecurringRuleResponse[]" />`. The page passes `activeRules`, and the component itself drops TRANSFER rules.
  - It renders nothing when no INCOME or EXPENSE rule remains.
  - It shows `recurring.summary.in` ("Entradas fixas" / "Fixed income") and `out` ("Saídas fixas" / "Fixed spending").
  - The third figure is `left` ("Sobra fixa por mês" / "Left each month") in the foreground color, or `short` ("Falta fixa por mês" / "Short each month") in `text-expense` with the absolute value when `netCents < 0`.
- `RecurringRuleRow`: for WEEKLY or YEARLY, and not TRANSFER, add under the amount `<span class="text-xs text-muted-foreground">` with `recurring.perMonth`: "≈ {amount}/mês" / "≈ {amount}/month", where `{amount}` is a `MoneyText` slot.

- [ ] **Step 1: Write failing tests:**
  - in `RecurringSummary.spec.ts`:
    - `summary_inOutLeft`: the Task 3 example rules give `€ 3.200,00`, `€ 1.123,33` and `€ 2.076,67`;
    - `summary_short_whenOutExceedsIn`: shows `Falta fixa por mês` with `text-expense`;
    - `summary_onlyTransfers_rendersNothing`;
  - in `RecurringPage.spec.ts`:
    - `row_weekly_showsMonthlyEquivalent`: a weekly 4000 rule's row contains `≈ € 173,33/mês`;
    - `row_monthly_noEquivalent`;
    - `summary_ignoresPausedAndFinished`: with only a paused and a finished rule, there is no `Entradas fixas`.
- [ ] **Step 2: Run** `npx vitest run src/features/recurring`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS. Commit `feat(web): recurring page shows the fixed monthly commitment`.

---

### Task 8: Objetivos total line

**Files:**
- Modify: `frontend/src/features/goals/pages/GoalsPage.vue`, `GoalsPage.spec.ts`, both locales

**Interfaces:**
- Under `SalaryCategoryPicker`, when `active.length > 0`, show `<i18n-t keypath="goals.savedTotal" tag="p" class="mb-4 text-sm text-muted-foreground">`: "{amount} guardados em {n} objetivo | {amount} guardados em {n} objetivos" / "{amount} saved across {n} goal | {amount} saved across {n} goals". It uses the `MoneyText` slot for `totalSavedCents` and the plural on `active.length`.

- [ ] **Step 1: Write failing tests:**
  - `savedTotal_showsAmountAndCount`: the setup's `totalSavedCents` 40000 and one goal give `€ 400,00 guardados em 1 objetivo`;
  - `savedTotal_noActiveGoals_hidden`.
- [ ] **Step 2: Run** `npx vitest run src/features/goals/pages`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS. Commit `feat(web): goals page shows the total saved`.

---

### Task 9: Verification and PR

- [ ] **Step 1:** Run the backend's `./mvnw -q verify`. In `frontend`, run `npx vitest run`, `npm run lint` and `npm run build`. Expected: all green, except the known CRLF-only `prettier --check` noise on untouched files, which must be stated if it appears.
- [ ] **Step 2:** Run `npm run e2e` with the dev Postgres up. Expected: 8/8.
- [ ] **Step 3:** Take throwaway screenshots (do not commit them) of Lançamentos filtered by a category and by an account, Orçamentos, Contas and Recorrências. Check them against the spec's sketches.
- [ ] **Step 4:** Push `feature/clarity-round-b` and open the PR with base `feature/dashboard-clarity`. Note in the body that it retargets to `develop` once #18 merges.
