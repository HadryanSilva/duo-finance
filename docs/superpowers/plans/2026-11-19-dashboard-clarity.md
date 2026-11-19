# Dashboard clarity and navigation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Rework the dashboard so every number carries its context (month summary, spending shares, budget remainders), move goal suggestions to the Goals page, and regroup the navigation.

**Architecture:** Frontend only. The arithmetic lives in pure functions in `features/dashboard/lib/figures.ts` with unit tests, and the components only render it. The suggestions UI moves out of `dashboard/components/GoalsCard.vue` into `goals/components/GoalSuggestions.vue`, fed by `useGoals(currentMonth())`, which already returns `suggestions`.

**Tech Stack:** Vue 3 `<script setup>`, vue-i18n, TanStack Vue Query, ECharts (via `LazyChart`), Tailwind tokens, Vitest + Testing Library + MSW, Playwright e2e.

**Spec:** `docs/superpowers/specs/2026-11-19-dashboard-clarity-design.md`

## Global Constraints

- No new colors, fonts, radii or tokens. The design guard (`src/test/design-guard.spec.ts`) must stay green: no hex, no arbitrary sizes, no palette classes.
- All copy goes through i18n in **both** `pt-BR.json` and `en.json`. `i18n.spec.ts` checks key parity.
- Money goes through `MoneyText` and `formatMoney`. In tests, pt-BR renders as `€ 1.234,56` (normalize spaces with `norm`).
- Green and red only for income and expense; amber `text-warning` only for budget alerts.
- No backend or API change; no route change.
- Branch `feature/dashboard-clarity` (from `develop`). The PR targets `develop`.
- Run commands from `frontend/`: tests with `npx vitest run <path>`, then `npm run lint` and `npm run build` at the end.

## Review Focus

1. **Expense greater than income.** The summary must read "Faltou" with a positive amount, never "Sobrou −X". Pinned in Task 1 (`monthSummary`) and Task 2.
2. **Zero income with expenses.** No "NaN%" or "Infinity%"; the share line is hidden. Pinned in Task 1.
3. **Tiny categories.** A category under 1% shows "<1%", not "0%". Pinned in Task 1.
4. **A past or future month on the dashboard.** There is no "faltam N dias" and no goal reminder. Pinned in Tasks 4 and 7.
5. **The last pending suggestion decided on the Goals page.** Focus lands on the "Tudo decidido" heading instead of being lost. Pinned in Task 6.

---

### Task 1: Pure figures

**Files:**
- Create: `frontend/src/features/dashboard/lib/figures.ts`
- Test: `frontend/src/features/dashboard/lib/figures.spec.ts`

**Interfaces:**
- Produces:
  - `monthSummary(incomeCents: number, expenseCents: number): { kind: 'left' | 'short'; amountCents: number; sharePercent: number | null }`
    - `kind` is `'short'` when expense > income;
    - `amountCents` is always ≥ 0;
    - `sharePercent` is `Math.round(left*100/income)`, only when kind is `'left'` and income > 0, otherwise `null`.
  - `type SpendingSlice = { key: string; categoryId: string | null; name: string | null; othersCount: number; color: string | null; totalCents: number; share: number }`
    - `name` and `color` are `null` and `othersCount > 0` for the "Outras" slice;
    - `key` is the category id or `'others'`.
  - `spendingSlices(spending: DashboardResponse['spendingByCategory']): SpendingSlice[]`
    - keeps the input order (the backend sorts it);
    - with ≤ 6 categories, every category is a slice;
    - with 7 or more, slices are the first 5 plus one others slice with the summed total and `othersCount = n - 5`;
    - `share` is `Math.round(total*100/sum)`.
  - `shareLabel(share: number, totalCents: number): string`: `'<1%'` when `totalCents > 0 && share < 1`, otherwise `` `${share}%` ``.
  - `daysLeftInMonth(month: string, today: string): number | null`
    - `null` unless `today` (ISO date) falls in `month` (`YYYY-MM`);
    - otherwise the days from today to the month end, counting today (last day → 1).
  - `budgetRemainder(spentCents: number, limitCents: number): { over: boolean; amountCents: number }`, with `amountCents` ≥ 0.

- [ ] **Step 1: Write the failing tests**

```ts
describe('monthSummary', () => {
  it('left_withShare', () =>
    expect(monthSummary(320000, 245000)).toEqual({ kind: 'left', amountCents: 75000, sharePercent: 23 }))
  it('expenseOverIncome_isShortWithPositiveAmount', () =>
    expect(monthSummary(100000, 120000)).toEqual({ kind: 'short', amountCents: 20000, sharePercent: null }))
  it('zeroIncome_hasNoShare', () =>
    expect(monthSummary(0, 0)).toEqual({ kind: 'left', amountCents: 0, sharePercent: null }))
  it('zeroIncomeWithExpense_isShort', () =>
    expect(monthSummary(0, 5000)).toEqual({ kind: 'short', amountCents: 5000, sharePercent: null }))
})
describe('spendingSlices', () => {
  const cat = (i: number, cents: number) => ({ categoryId: `c${i}`, name: `C${i}`, color: '#000000', icon: 'gift', totalCents: cents })
  it('sixOrFewer_allListed', () => {
    const s = spendingSlices([1, 2, 3, 4, 5, 6].map((i) => cat(i, 1000)))
    expect(s).toHaveLength(6)
    expect(s.every((x) => x.othersCount === 0)).toBe(true)
  })
  it('sevenOrMore_fivePlusOthers', () => {
    const s = spendingSlices([1, 2, 3, 4, 5, 6, 7].map((i) => cat(i, 1000)))
    expect(s).toHaveLength(6)
    expect(s[5]).toMatchObject({ key: 'others', categoryId: null, name: null, othersCount: 2, totalCents: 2000, share: 29 })
  })
  it('shares_areRoundedPercentOfTotal', () =>
    expect(spendingSlices([cat(1, 45000), cat(2, 12050)]).map((x) => x.share)).toEqual([79, 21]))
})
describe('shareLabel', () => {
  it('underOnePercent', () => expect(shareLabel(0, 10)).toBe('<1%'))
  it('normal', () => expect(shareLabel(40, 98000)).toBe('40%'))
})
describe('daysLeftInMonth', () => {
  it('countsToday', () => expect(daysLeftInMonth('2026-11', '2026-11-20')).toBe(11))
  it('lastDayIsOne', () => expect(daysLeftInMonth('2026-02', '2026-02-28')).toBe(1))
  it('otherMonth_isNull', () => expect(daysLeftInMonth('2026-10', '2026-11-20')).toBeNull())
})
describe('budgetRemainder', () => {
  it('under', () => expect(budgetRemainder(98000, 100000)).toEqual({ over: false, amountCents: 2000 }))
  it('exactlyAtLimit_isNotOver', () => expect(budgetRemainder(100000, 100000)).toEqual({ over: false, amountCents: 0 }))
  it('over', () => expect(budgetRemainder(36750, 30000)).toEqual({ over: true, amountCents: 6750 }))
})
```

- [ ] **Step 2: Run** `npx vitest run src/features/dashboard/lib/figures.spec.ts`. Expected: FAIL (module not found).
- [ ] **Step 3: Implement** the five functions in `figures.ts`. Do the date math on the ISO strings, or with UTC `Date`, so local time zones cannot shift the day.
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(web): dashboard figures for summary, spending shares and budget remainders`

---

### Task 2: Month summary card, and removal of the income × expense chart

**Files:**
- Create: `frontend/src/features/dashboard/components/MonthSummaryCard.vue`, `MonthSummaryCard.spec.ts`
- Delete: `frontend/src/features/dashboard/components/IncomeExpenseChart.vue`, `IncomeExpenseChart.spec.ts`
- Modify: `frontend/src/features/dashboard/lib/chart-options.ts` (delete `incomeExpenseOption`), `chart-options.spec.ts` (delete `incomeExpenseTooltip_escapesLabels` and `incomeExpenseOption_drawsBarsOnAxes`), and both locale files

**Interfaces:**
- Consumes: `monthSummary` (Task 1).
- Produces: `<MonthSummaryCard :data="DashboardResponse | undefined" />`.
- i18n: add `dashboard.summary.{label, in, out, left, short, leftShare}`:
  - pt-BR: "Resumo do mês", "Entrou", "Saiu", "Sobrou", "Faltou", "{percent}% do que entrou";
  - en: "Month summary", "In", "Out", "Left over", "Short by", "{percent}% of what came in".
  - Remove `dashboard.incomeExpense`.

Markup:
- the card is a `<dl :aria-label="summary.label">` with three `dt`/`dd` pairs;
- "Entrou" uses `MoneyText kind="INCOME"` and "Saiu" uses `kind="EXPENSE"`;
- the third pair is "Sobrou" (foreground) or "Faltou" (`text-expense`);
- the amounts use `text-2xl font-semibold`;
- the layout is `grid gap-4 sm:grid-cols-3`;
- while `data` is undefined, show three `Skeleton`s.

- [ ] **Step 1: Write failing tests** in `MonthSummaryCard.spec.ts` (render with `props: { data }`, as in `SpendingDonut.spec.ts`):
  - `summary_showsInOutAndLeftWithShare`: income 320000 and expense 245000. The text contains `€ 3.200,00`, `€ 2.450,00`, `Sobrou`, `€ 750,00` and `23% do que entrou`.
  - `summary_expenseOverIncome_saysShort`: 100000 and 120000. The text contains `Faltou` and `€ 200,00`, and contains neither `Sobrou` nor `do que entrou`.
  - `summary_zeroIncome_hidesShare`: 0 and 0. The text does not contain `%`.
  - `loading_showsSkeletons`.
- [ ] **Step 2: Run** `npx vitest run src/features/dashboard/components/MonthSummaryCard.spec.ts`. Expected: FAIL.
- [ ] **Step 3: Implement** the card. Delete the chart files, the option and the two option tests, and update the locales.
- [ ] **Step 4: Run** `npx vitest run src/features/dashboard src/shared/i18n`. Expected: the new tests pass. `DashboardPage.spec` may now fail where it imports or expects the removed chart; Task 8 fixes it, so note it and move on.
- [ ] **Step 5: Commit** `feat(web): month summary replaces the income/expense bar chart`

---

### Task 3: "Para onde foi o dinheiro"

**Files:**
- Modify: `frontend/src/features/dashboard/components/SpendingDonut.vue`, `SpendingDonut.spec.ts`, `lib/chart-options.ts` (`spendingOption`), `lib/chart-options.spec.ts`, both locales

**Interfaces:**
- Consumes: `spendingSlices` and `shareLabel` (Task 1). The page passes `month` (`YYYY-MM`) as a new prop.
- `spendingOption(slices: SpendingSlice[], colors: ChartColors, locale: Locale, othersName: (n: number) => string)`. The others slice uses `colors.muted`.
- Route for a named slice: `{ path: '/transactions', query: { from, to, categoryId } }`, with `from` and `to` from `monthRange(month)` in `features/transactions/api/transactions-api.ts`.
- i18n:
  - `dashboard.spending.title` becomes "Para onde foi o dinheiro" / "Where the money went";
  - add `dashboard.spending.others`: "Outras {n}" / "{n} others";
  - add `dashboard.spending.linkLabel`: "Ver lançamentos de {name} ({share})" / "See {name} transactions ({share})";
  - keep `legendLabel` (the e2e uses "Gastos por categoria").

Legend row:
- dot, name, `MoneyText`, and the share in a `w-10 text-right tabular-nums text-muted-foreground` span;
- a named row is a `RouterLink` covering the whole row, with `hover:bg-accent rounded-md` and visible focus;
- the others row is plain text.

- [ ] **Step 1: Update and add failing tests:**
  - `legend_listsEachCategoryWithItsAmount`: also expect `79%` on Mercado and `21%` on Lazer.
  - `legend_rowLinksToTransactionsOfThatCategoryAndMonth`: with `month: '2026-03'`, the Mercado link's href is `/transactions?from=2026-03-01&to=2026-03-31&categoryId=c1`.
  - `legend_sevenCategories_groupsOthers`: 7 categories give 6 list items; the last contains `Outras 2` and is not a link; the chart data has 6 points, and the last point's color equals `colors.muted`.
  - `chart-options.spec`: update `spendingOption_drawsDoughnutSlices` and `spendingTooltip_escapesUserEnteredNames` to pass slices.
- [ ] **Step 2: Run** `npx vitest run src/features/dashboard/components/SpendingDonut.spec.ts src/features/dashboard/lib/chart-options.spec.ts`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(web): spending card shows each category's share and links to its transactions`

---

### Task 4: Budgets card with remainders and days left

**Files:**
- Modify: `frontend/src/features/dashboard/components/BudgetsCard.vue`, both locales
- Create: `frontend/src/features/dashboard/components/BudgetsCard.spec.ts`

**Interfaces:**
- Consumes: `budgetRemainder` and `daysLeftInMonth` (Task 1), `todayIso()` from `shared/lib/dates`, and a new prop `month`.
- i18n under `dashboard.budgets`:
  - `daysLeft`: "falta {n} dia | faltam {n} dias" / "{n} day left | {n} days left";
  - `lastDay`: "último dia do mês" / "last day of the month";
  - `remaining`: "restam {amount}" / "{amount} left";
  - `over`: "passou {amount}" / "{amount} over";
  - `spentOf`: "{spent} de {limit}" / "{spent} of {limit}".
- Use `i18n-t` slots with `MoneyText` for the amounts. With N = 1 the header shows `lastDay` instead of `daysLeft`.
- Row text color: `text-expense` when over, `text-warning` when `status === 'WARNING'`, otherwise `text-foreground`.
- The header shows the days next to the `CardTitle`, in `text-sm text-muted-foreground`.

- [ ] **Step 1: Write failing tests.** Fake time with `vi.useFakeTimers(); vi.setSystemTime(new Date('2026-11-20T12:00:00'))`, then restore it in `afterEach`.
  - `currentMonth_showsDaysLeft`: `month '2026-11'` shows `faltam 11 dias`.
  - `lastDay_saysLastDay`: system time 2026-11-30 shows `último dia do mês`.
  - `pastMonth_hidesDaysLeft`: `month '2026-10'` does not contain `falta`.
  - `underLimit_showsRemaining`: spent 98000 and limit 100000 show `restam € 20,00` and `€ 980,00 de € 1.000,00`.
  - `overLimit_showsOverInExpenseColor`: spent 36750 and limit 30000. The element with text `passou € 67,50` has class `text-expense`.
  - `empty_offersCta`: an empty list shows the "Definir orçamento" link.
- [ ] **Step 2: Run** `npx vitest run src/features/dashboard/components/BudgetsCard.spec.ts`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(web): budgets card says what is left and how many days remain`

---

### Task 5: Compact balance and upcoming cards

**Files:**
- Modify: `frontend/src/features/dashboard/components/BalanceCard.vue`, `UpcomingCard.vue`, `UpcomingCard.spec.ts`, both locales

**Interfaces:**
- Balance:
  - the total moves to `text-2xl`;
  - the free-balance block becomes one line, "Saldo livre <amount>", on the same visibility rule;
  - `freeHint` and `freeNegative` stay as `text-sm` lines under it;
  - the accounts list is unchanged;
  - the existing `DashboardPage.spec` balance tests must still pass unchanged.
- Upcoming:
  - shows `data.upcoming.slice(0, 5)`;
  - when the list is longer, a `Button variant="link" size="sm"` `RouterLink` to `/recurring` sits in the header, with text `dashboard.upcoming.viewAll` ("Ver todos" / "See all") and aria-label `dashboard.upcoming.viewAllLabel` ("Ver todas as recorrências" / "See all recurring rules").

- [ ] **Step 1: Add failing tests** to `UpcomingCard.spec.ts`:
  - `moreThanFive_showsFiveAndLinkToRecurring`: 7 occurrences give 5 list items, and a link named "Ver todas as recorrências" with href `/recurring`.
  - `fiveOrFewer_noLink`.
- [ ] **Step 2: Run** `npx vitest run src/features/dashboard/components/UpcomingCard.spec.ts`. Expected: FAIL.
- [ ] **Step 3: Implement both cards.**
- [ ] **Step 4: Run** `npx vitest run src/features/dashboard`. Expected: the Upcoming tests and the balance tests in `DashboardPage.spec` pass.
- [ ] **Step 5: Commit** `feat(web): compact balance and upcoming cards`

---

### Task 6: Goal suggestions on the Goals page

**Files:**
- Create: `frontend/src/features/goals/components/GoalSuggestions.vue`, `GoalSuggestions.spec.ts`
- Modify: `frontend/src/features/goals/pages/GoalsPage.vue`, `GoalsPage.spec.ts`, both locales, `frontend/e2e/goals.spec.ts`

**Interfaces:**
- Produces: `<GoalSuggestions :overview="GoalsOverview" />`. `GoalsOverview` is the type from `@/shared/api/types` that `useGoals` returns.
- Moved behavior: everything in `GoalsCard.vue` from `adjusting` to `saveAdjusted`, the suggestions `<section>`, the `failure` paragraph for a row that is gone, and the `emptySalaryCategory` message. The `needsSalaryCategory` link is **not** moved, because the page has `SalaryCategoryPicker`. Two changes:
  - **Root:**
    - `<section ref="root" aria-labelledby="goal-suggestions-title">`, rendered when `overview.suggestions.length > 0 || decidedHere || failure || emptySalaryCategory`;
    - `focusIn` queries `root`;
    - the heading is `<h2 id="goal-suggestions-title" data-goals-title tabindex="-1" class="text-lg font-semibold outline-none">`;
    - the heading's text is the `suggestionsHeader` interpolation while suggestions exist, otherwise `goals.suggestions.allDone`;
    - `decidedHere` is a `ref(false)` set to `true` after any successful save.
  - **i18n:**
    - move these keys from `dashboard.goals.*` to `goals.suggestions.*`: `suggestionsHeader`→`header`, `suggestionsLabel`→`listLabel`, `confirm`, `adjust`, `confirmLabel`, `failureFor`, `adjustLabel`, `skipLabel`, `amountFor`, `amountTooHigh`, `noSalary`;
    - keep the pt-BR values, so `listLabel` stays "Sugestões do mês" and the e2e locator holds;
    - add `allDone`: "Tudo decidido em {month}." / "All decided for {month}.".
- GoalsPage: render `<GoalSuggestions v-if="goals.data.value" :overview="goals.data.value" class="mb-6" />` between `SalaryCategoryPicker` and the list.

- [ ] **Step 1: Move the tests.**
  - Copy every suggestion-related test from `GoalsCard.spec.ts` into `GoalSuggestions.spec.ts`: confirm, adjust, skip, the failures, the amount validation, the focus tests, `noSalaryThisMonth_*` and `confirm_failure_refreshesGoalsAndDashboard`.
  - Mount with `props: { overview }`, where `overview` is the `goals` object those tests already build, without the `DashboardResponse` wrapper.
  - In the "partner already decided" helpers, `setProps({ overview: ... })` replaces `setProps({ data: ... })`.
  - Change `skip_lastSuggestion_movesFocusToTheTitle` to expect focus on the heading with text `Tudo decidido em novembro de 2026.`. Use the month formatted with `formatMonth`, as the existing test does.
  - Add `GoalsPage.spec`: `suggestions_areShownAboveTheList`. MSW's `/goals` returns one suggestion, so the button "Confirmar Videogame novo" is present.
- [ ] **Step 2: Run** `npx vitest run src/features/goals`. Expected: FAIL (component missing).
- [ ] **Step 3: Implement** the component and the page change, and move the keys in both locales. In `e2e/goals.spec.ts`:
  - replace `page.goto('/dashboard')` before the suggestions with `page.goto('/goals')`;
  - change the `Andamento dos objetivos` assertion to the Goals page list, or keep it after a `goto('/dashboard')`, since the compact card keeps that list label;
  - keep the `Saldo livre` check on the dashboard.
- [ ] **Step 4: Run** `npx vitest run src/features/goals src/shared/i18n`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(web): monthly goal suggestions move to the Goals page`

---

### Task 7: Compact goals card with the pending reminder

**Files:**
- Modify: `frontend/src/features/dashboard/components/GoalsCard.vue`, `GoalsCard.spec.ts`, both locales

**Interfaces:**
- Consumes: `currentMonth()`, `formatMonth`.
- The card keeps:
  - the header with "Ver todos";
  - the empty state;
  - the `needsSalaryCategory` link;
  - the progress list (`listLabel` "Andamento dos objetivos").
- Everything moved in Task 6 is deleted from the card.
- New line, shown when `goals.suggestions.length > 0 && props.month === currentMonth()`:
  - a `RouterLink` to `/goals` with `dashboard.goals.pending`: "{n} contribuição para confirmar em {month} | {n} contribuições para confirmar em {month}" / "{n} contribution to confirm for {month} | {n} contributions to confirm for {month}";
  - style `text-sm font-medium text-brand underline-offset-4 hover:underline`.
- New prop: `month: string`.

- [ ] **Step 1: Rewrite `GoalsCard.spec.ts`:**
  - keep `empty_offersCreate`, `progress_showsSavedOverTarget`, `reached_showsBadge` and the `noSalaryCategory`, `archivedSalaryCategory` and `usableSalaryCategory` hint tests;
  - delete the tests moved in Task 6;
  - add `pending_currentMonth_linksToGoals`: 2 suggestions and `month = currentMonth()` give a link named `2 contribuições para confirmar em <formatMonth>` with href `/goals`;
  - add `pending_pastMonth_isHidden`: `month = addMonths(currentMonth(), -1)` shows no such link;
  - add `noConfirmButtonsOnDashboard`: `queryByRole('button', { name: /Confirmar/ })` is null.
- [ ] **Step 2: Run** `npx vitest run src/features/dashboard/components/GoalsCard.spec.ts`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** the same command. Expected: PASS.
- [ ] **Step 5: Commit** `feat(web): dashboard goals card shows progress and a reminder to confirm`

---

### Task 8: Dashboard layout

**Files:**
- Modify: `frontend/src/features/dashboard/pages/DashboardPage.vue`, `DashboardPage.spec.ts`, `frontend/e2e/onboarding.spec.ts` (only if a locator broke)

**Interfaces:**
- Consumes: every card above. `month` is passed to `SpendingDonut`, `BudgetsCard` and `GoalsCard`.

Layout:
```
<MonthSelector>
<MonthSummaryCard class="mb-4" />
grid gap-4 lg:grid-cols-5:   SpendingDonut lg:col-span-3 | BudgetsCard lg:col-span-2
grid gap-4 md:grid-cols-2 lg:grid-cols-3:   BalanceCard | UpcomingCard | GoalsCard (md:col-span-2 lg:col-span-1)
RecentTransactionsCard
```
- Use `mb-4` between groups, or wrap them in one `grid gap-4`; pick one rule and keep it.
- On error, the whole block except `RecentTransactionsCard` is hidden, as today.

- [ ] **Step 1: Update `dashboard_rendersAllCardsFromResponse`:**
  - the "income and expense totals" section now also expects `Sobrou`;
  - the chart count becomes `toHaveLength(1)` (only the doughnut);
  - add `dashboard_cardsInReadingOrder`: the document order of the headings is Resumo/summary, then "Para onde foi o dinheiro", "Orçamentos", "Saldo", "Próximos", "Objetivos", "Últimos lançamentos". Compare the `indexOf` of each text in `document.body.textContent`.
- [ ] **Step 2: Run** `npx vitest run src/features/dashboard/pages`. Expected: FAIL.
- [ ] **Step 3: Implement** the layout.
- [ ] **Step 4: Run** `npx vitest run`. Expected: the whole suite passes, including the design guard and i18n parity.
- [ ] **Step 5: Commit** `feat(web): dashboard leads with the month summary`

---

### Task 9: Navigation groups

**Files:**
- Modify: `frontend/src/app/components/nav.ts`, `AppSidebar.vue`, `UserMenu.vue`, `frontend/src/app/layouts/AppLayout.spec.ts`, both locales

**Interfaces:**
- `nav.ts` exports:
  - `DAILY_ITEMS = [DASHBOARD, TRANSACTIONS, BUDGETS, GOALS]`;
  - `SETUP_ITEMS = [BANK_ACCOUNTS, CATEGORIES, RECURRING]`;
  - `SETTINGS`, `ADMIN`;
  - `MORE_ITEMS = [GOALS]`, the daily items the bottom nav lacks;
  - remove `SIDEBAR_ITEMS`.
- i18n: `nav.setup` = "Cadastros" / "Setup".
- Sidebar:
  - first nav list: the daily items;
  - then a `<p class="px-3 text-xs font-medium text-muted-foreground">` heading, and a second list with `aria-labelledby` pointing at it;
  - Configurações and, for admins, Admin go in a list pushed to the bottom with `mt-auto`.
- User menu:
  - "Mais" group: `MORE_ITEMS`;
  - `DropdownMenuSeparator`;
  - a `DropdownMenuGroup` labelled `nav.setup` with `SETUP_ITEMS`;
  - then Admin for admins.

- [ ] **Step 1: Update `AppLayout.spec.ts`:**
  - sidebar link order: `['Duo Finance','Dashboard','Lançamentos','Orçamentos','Objetivos','Contas','Categorias','Recorrências','Configurações']`;
  - add `sidebar_groupsSetupItems`: `getByRole('list', { name: 'Cadastros' })` contains exactly Contas, Categorias and Recorrências;
  - menu items for USER: `['Objetivos','Contas','Categorias','Recorrências','Sair']`;
  - for ADMIN, the same with `'Admin'` before `'Sair'`;
  - in English: `['Goals','Accounts','Categories','Recurring','Sign out']`;
  - the `it.each` reach-from-menu cases are unchanged;
  - the menu text contains `Cadastros`.
- [ ] **Step 2: Run** `npx vitest run src/app`. Expected: FAIL.
- [ ] **Step 3: Implement.**
- [ ] **Step 4: Run** `npx vitest run src/app`. Expected: PASS.
- [ ] **Step 5: Commit** `feat(web): navigation separates daily pages from setup`

---

### Task 10: Verification

- [ ] **Step 1:** In `frontend/`, run `npx vitest run`, `npm run lint` and `npm run build`. Expected: all green.
- [ ] **Step 2:** Run `npm run e2e` against the local stack, or say plainly that it was not run and why. Expected: `goals.spec` and `onboarding.spec` pass.
- [ ] **Step 3:** Start the app (`run` skill) and screenshot the dashboard in light, dark and mobile 375 px, and the Goals page with a pending suggestion. Check the spec's layout and the Review Focus items by eye.
- [ ] **Step 4:** Push and open the PR against `develop`.
