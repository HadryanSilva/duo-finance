# Month review: category weight and budget follow-up Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The Revisão page shows each spending category's share of the month, can order categories by size or by change, and shows each category's current budget with a Definir/Ajustar action that opens the budget form prefilled.

**Architecture:**
- Frontend only.
- `ReviewPage` runs the existing `useBudgets(month)` next to `useMonthReview(month)`. `ReviewCategories` joins budgets to rows by category id.
- Pure logic lives in `features/review/lib/review.ts`: share, order, suggested limit.
- `BudgetForm` gains optional props for a fixed category and a suggestion. It opens in the same `SidePanel` the Orçamentos page uses.

**Tech Stack:** Vue 3 `<script setup>`, TypeScript, TanStack Vue Query, vue-i18n, Vitest + Testing Library + MSW.

**Spec:** `docs/superpowers/specs/2026-11-22-month-review-budgets-design.md`

## Global Constraints

- No backend change, no migration, and no change to the `/month-review` or `/budgets` contracts.
- The Orçamentos page and the dashboard behave exactly as before.
- **Budget status** comes from the API's `BudgetResponse.status` for the reviewed month (NORMAL / WARNING / EXCEEDED). Never recompute it.
- **Share:**
  - `Math.round(cents * 100 / expense.cents)`, the same rounding as the dashboard donut.
  - "<1%" for a real share under 1%.
  - "—" for a category at €0 this month.
- **Default order "Maior gasto":** the month's amount (descending), then name.
- **"Maior mudança":** today's order (absolute difference, then amount, then name).
- **No comparison** (`comparedMonths === 0`): hide the toggle and order by size.
- **Suggested limit:**
  - `averageCents` when it is not null and above 0, otherwise the month's `cents`;
  - rounded **up** to a whole euro (a multiple of 100 cents).
- **Suggestion label:** "média dos últimos N meses" (pluralised by `comparedMonths`) or "gasto deste mês". It is never worded as advice.
- **Ajustar:** the limit field starts on the current limit, and a "Usar sugestão" button sets the suggestion.
- **Definir:** the limit field starts on the suggestion.
- **Archived categories:** no action button, because the API rejects them with `CATEGORY_ARCHIVED`.
- **Mobile (< `sm`):**
  - the average and % columns are hidden;
  - the budget and the action stay;
  - the action is an icon button with an aria-label.
- **A failing budgets request** never hides the review: only the budget column shows an inline error.
- **Status markers** always carry text (the icon is `aria-hidden` plus visible or sr-only text). Colour is never the only signal.
- **Colours** use only the design tokens: `text-expense`, `text-warning`, `text-income`, `text-muted-foreground`.
- **Strings:** every new string is in both `src/shared/i18n/locales/pt-BR.json` and `en.json`, under `review.*`.

## Review Focus

1. **A failing budgets request:** the review table still renders with every row, and the budget column shows an inline error. Tested in Task 3 by `reviewPage_budgetsErrorKeepsReview`.
2. **Changing month:** the budget column shows that month's status (a new `?month=`), not the previous month's. Tested in Task 3 by `reviewPage_budgetsFollowMonth`.
3. **After saving a budget in the panel:** the panel closes and the column shows the new limit without a reload. Tested in Task 3 by `reviewPage_definirCreatesBudgetAndRefreshes`.
4. **An archived category:** it still shows its budget, if any, and offers no action. Tested in Task 3 by `reviewPage_archivedCategoryHasNoAction`.
5. **The household's first month:** the toggle is hidden, and the suggestion uses the month's amount, labelled "gasto deste mês". Tested in Task 3 by `reviewPage_firstMonthSuggestsMonthAmount`.

---

### Task 1: Review row share, order and suggested limit

**Files:**
- Modify: `src/features/review/lib/review.ts`
- Modify: `src/features/review/lib/review.spec.ts`
- Modify: `src/features/dashboard/lib/figures.ts` (move `shareLabel` out)
- Modify: `src/shared/lib/figures.ts` (receive `shareLabel`)
- Modify: `src/features/dashboard/components/SpendingDonut.vue` (import path), plus any spec importing `shareLabel` (move its tests to the shared lib's spec, if one exists, or keep them pointing at the new path)
- Modify: `src/features/review/components/ReviewCategories.vue` (only the call site, so the build stays green)

**Interfaces:**
- Produces:
  - `export type ReviewOrder = 'size' | 'change'`
  - `ReviewRow` gains `share: number`, the whole percent of the month's expense.
  - `reviewRows(categories: MonthReviewResponse['categories'], expenseCents: number, order: ReviewOrder): ReviewRow[]`
  - `shareText(row: Pick<ReviewRow, 'cents' | 'share'>): string`, which returns `'—'` when `cents === 0` and `shareLabel(share, cents)` otherwise.
  - `suggestedLimit(row: Pick<ReviewRow, 'cents' | 'averageCents'>): { cents: number; source: 'average' | 'month' }`
  - `shareLabel(share: number, totalCents: number): string`, now exported from `@/shared/lib/figures`.

- [ ] **Step 1: Write the failing tests in `review.spec.ts`**
  - Update the existing `reviewRows(...)` calls to `reviewRows(categories, expenseCents, 'change')`. Their expected orders stay as they are.
  - Add the cases below, built from the existing spec's category helper. The sketch data is Casa 90000 / 90000, Mercado 52000 / 43000, Restaurantes 21000 / 12000, Transporte 8000 / 14000, with expense 245000.

```ts
it('reviewRows_sizeOrderByAmountThenName', () => {
  const rows = reviewRows(sketch, 245000, 'size')
  expect(rows.map((r) => r.name)).toEqual(['Casa', 'Mercado', 'Restaurantes', 'Transporte'])
  // equal amounts fall back to name
  const tie = reviewRows([cat('b', 'Bravo', 1000, null), cat('a', 'alfa', 1000, null)], 2000, 'size')
  expect(tie.map((r) => r.name)).toEqual(['alfa', 'Bravo'])
})

it('reviewRows_shareOfMonthExpense', () => {
  const rows = reviewRows(sketch, 245000, 'size')
  expect(rows.map((r) => r.share)).toEqual([37, 21, 9, 3])
})

it('shareText_tinyAndZero', () => {
  expect(shareText({ cents: 100, share: 0 })).toBe('<1%')
  expect(shareText({ cents: 0, share: 0 })).toBe('—')
  expect(shareText({ cents: 52000, share: 21 })).toBe('21%')
})

it('suggestedLimit_averageRoundedUpToEuro', () => {
  expect(suggestedLimit({ cents: 52000, averageCents: 43001 })).toEqual({ cents: 43100, source: 'average' })
  expect(suggestedLimit({ cents: 52000, averageCents: 43000 })).toEqual({ cents: 43000, source: 'average' })
})

it('suggestedLimit_monthWhenNoUsefulAverage', () => {
  expect(suggestedLimit({ cents: 21050, averageCents: null })).toEqual({ cents: 21100, source: 'month' })
  expect(suggestedLimit({ cents: 21000, averageCents: 0 })).toEqual({ cents: 21000, source: 'month' })
})
```

- [ ] **Step 2: Run the tests to see them fail**

Run: `rtk vitest run src/features/review/lib`
Expected: FAIL. `shareText` / `suggestedLimit` aren't exported, and `share` is undefined.

- [ ] **Step 3: Implement the changes**
  - **`shareLabel`:** move it to `src/shared/lib/figures.ts` unchanged, and update its importers.
  - **`reviewRows`:**
    - computes `share` with the Global Constraints rounding (0 when `expenseCents` is 0);
    - sorts by `order` (the size comparator is amount descending, then the existing `byName`).
  - **`ReviewCategories.vue`:** pass `props.data.expense.cents` and `'change'` for now. Task 3 replaces this.

- [ ] **Step 4: Run the tests, the type check and the lint**

Run: `rtk vitest run src/features/review src/features/dashboard && rtk npx vue-tsc -b && rtk npm run lint`
Expected: all PASS, no type errors.

- [ ] **Step 5: Commit**

```bash
rtk git add src/features/review src/features/dashboard src/shared/lib
rtk git commit -m "feat(web): month review rows carry their share, a size order and a suggested budget"
```

---

### Task 2: BudgetForm with a fixed category and a suggestion

**Files:**
- Modify: `src/features/budgets/components/BudgetForm.vue`
- Create: `src/features/budgets/components/BudgetForm.spec.ts`
- Modify: `src/shared/i18n/locales/pt-BR.json`, `en.json` (`budgets.form.useSuggestion`)

**Interfaces:**
- Produces new optional props on `BudgetForm`. All of them are ignored when absent, so the Orçamentos page's use is unchanged.
  - `category?: { id: string; name: string }`
    - When creating, the category is fixed. Its name is shown the way the edit mode shows `budget.category.name`, with no select.
    - `categoryId` starts as `category.id`.
  - `suggestion?: { cents: number; label: string }`
    - **Creating:** the limit field starts on `suggestion.cents`.
    - **Editing** (`budget` given): the field starts on `budget.monthlyLimitCents`. Below it sits a muted line with `label`, plus a ghost/link button "Usar sugestão: {amount}" that sets the field to `suggestion.cents`.
    - **When the field already equals the suggestion:** the button is hidden and the label still shows.
- i18n: `budgets.form.useSuggestion`
  - pt-BR: "Usar sugestão: {amount}"
  - en: "Use suggestion: {amount}"

- [ ] **Step 1: Write the failing spec `BudgetForm.spec.ts`**
  - Mount it with the project's test render helper (see how `BudgetsPage.spec.ts` mounts pages and provides MSW handlers; mount the component inside a minimal route or with the same providers).
  - Tests:
    - `budgetForm_fixedCategoryStartsOnSuggestion`:
      - Props: `category={id:'c-rest',name:'Restaurantes'}` and `suggestion={cents:12000,label:'média dos últimos 3 meses'}`.
      - Expect the text "Restaurantes", no combobox, the limit input's value showing €120,00, and the text "média dos últimos 3 meses".
      - On submit, `POST /budgets` receives `{ categoryId: 'c-rest', monthlyLimitCents: 12000 }` and `done` is emitted.
    - `budgetForm_editKeepsLimitAndOffersSuggestion`:
      - Props: a `budget` with `monthlyLimitCents: 45000` and a suggestion of 43000.
      - The input starts at €450,00.
      - Clicking "Usar sugestão: € 430,00" sets it to €430,00.
      - On submit, `PUT /budgets/{id}` receives `{ monthlyLimitCents: 43000 }`.
    - `budgetForm_withoutNewPropsUnchanged`:
      - No new props.
      - The category select is present, and no "Usar sugestão" button exists.

- [ ] **Step 2: Run it to see it fail**

Run: `rtk vitest run src/features/budgets/components/BudgetForm.spec.ts`
Expected: FAIL. The props don't exist, and a combobox is shown.

- [ ] **Step 3: Implement the props in `BudgetForm.vue`**
  - The initial values derive from `budget ?? category` and `suggestion` as listed in Interfaces.
  - Format the amount in the button with `formatMoney(cents, locale)` from `@/shared/lib/money`.

- [ ] **Step 4: Run the budget tests, the type check and the lint**

Run: `rtk vitest run src/features/budgets && rtk npx vue-tsc -b && rtk npm run lint`
Expected: all PASS, including the existing `BudgetsPage.spec.ts`.

- [ ] **Step 5: Commit**

```bash
rtk git add src/features/budgets src/shared/i18n
rtk git commit -m "feat(web): budget form can fix its category and offer a suggested limit"
```

---

### Task 3: Revisão table with share, order toggle, budget column and Definir/Ajustar

**Files:**
- Modify: `src/features/review/components/ReviewCategories.vue`
- Create: `src/features/review/components/ReviewBudgetCell.vue` (the status cell)
- Modify: `src/features/review/pages/ReviewPage.vue`
- Modify: `src/features/review/pages/ReviewPage.spec.ts`
- Modify: `src/shared/i18n/locales/pt-BR.json`, `en.json`

**Interfaces:**
- Consumes:
  - `reviewRows`, `shareText`, `suggestedLimit` and `ReviewOrder` from Task 1;
  - the `BudgetForm` props `category` and `suggestion` from Task 2;
  - `useBudgets(month)` from `@/features/budgets/api/budgets-api`;
  - `SidePanel` from `@/shared/ui/SidePanel.vue`.
- Produces:
  - `ReviewCategories` props: `data: MonthReviewResponse`, `budgets: BudgetResponse[] | undefined`, `budgetsError: boolean`.
  - `ReviewCategories` emits `budget: [row: ReviewRow, budget: BudgetResponse | null]`.
  - The order toggle is local state inside `ReviewCategories` (`ref<ReviewOrder>('size')`).
  - `ReviewBudgetCell` props: `budget: BudgetResponse | null`, `loading: boolean`, `error: boolean`.
- `ReviewPage`:
  - calls `useBudgets(() => month.value ?? '')` with `enabled` only once `month` is known. Add an optional `enabled` getter to `useBudgets` if it has none, with a default of `true`.
  - holds `panel: { row: ReviewRow; budget: BudgetResponse | null } | null`;
  - renders `SidePanel` with `BudgetForm` (`:budget`, `:category="{ id: row.categoryId, name: row.name }"`, `:suggestion`, `:key` = row id).
  - The title is `budgets.createTitle` / `budgets.editTitle`.
- i18n `review.*` (pt-BR / en):

| key | pt-BR | en |
|---|---|---|
| `orderLabel` | "Ordenar por" | "Sort by" |
| `orderSize` | "Maior gasto" | "Largest spend" |
| `orderChange` | "Maior mudança" | "Largest change" |
| `share` | "% da saída" | "% of spending" |
| `budget` | "Orçamento atual" | "Current budget" |
| `budgetHint` | "Limite de hoje aplicado a este mês" | "Today's limit applied to this month" |
| `budgetNormal` | "dentro do orçamento" | "within budget" |
| `budgetWarning` | "perto do limite" | "near the limit" |
| `budgetExceeded` | "estourou" | "over budget" |
| `budgetError` | "Não foi possível carregar os orçamentos." | "Couldn't load the budgets." |
| `setBudget` | "Definir" / aria "Definir orçamento de {name}" (`setBudgetLabel`) | "Set" / "Set budget for {name}" |
| `adjustBudget` | "Ajustar" / aria "Ajustar orçamento de {name}" (`adjustBudgetLabel`) | "Adjust" / "Adjust budget for {name}" |
| `suggestionAverage` | "média dos últimos {n} mês \| média dos últimos {n} meses" | "average of the last {n} month \| average of the last {n} months" |
| `suggestionMonth` | "gasto deste mês" | "this month's spending" |

- **Layout:**
  - **Toggle:** a `role="group"` with `aria-label=orderLabel` and two small `Button`s with `aria-pressed`, in the card header next to the title.
  - **Columns:**
    - category;
    - month;
    - share (`hidden sm:table-cell`);
    - average (already `hidden sm:table-cell`);
    - difference;
    - budget, whose header carries `title` = budgetHint plus an sr-only hint;
    - action.
  - **Cell:** `ReviewBudgetCell` shows `MoneyText` of the limit plus a marker.
    - EXCEEDED: the text `budgetExceeded` in `text-expense`.
    - WARNING: `budgetWarning` in `text-warning`.
    - NORMAL: a `Check` icon in `text-income`, `aria-hidden`, with sr-only `budgetNormal`.
    - "—" when there is no budget, a `Skeleton` while loading, and `budgetError` muted on error.
  - **Action:** from `sm` up, a text button (Definir/Ajustar). Below `sm`, an icon button (`Plus` / `Pencil` from `@lucide/vue`) with the aria-label key. Hidden for `archived` rows.
  - **Errors:** when budgets errored, show the error once, in the first row's cell or as a line under the table (the builder's choice), not in every row.

- [ ] **Step 1: Update and extend `ReviewPage.spec.ts`**
  - **Handlers:** `setup()` also serves `GET /budgets` from a per-test function of the month.
    - It defaults to `[]`, so the existing tests keep passing without unhandled requests (MSW errors on them).
    - Build a `budget(categoryId, name, limit, spent, status, month)` helper matching `BudgetResponse`.
  - **Change the order expectation** in `reviewPage_showsSummaryAndCategoriesOrdered`:
    - the default is `['Casa', 'Mercado', 'Restaurantes', 'Transporte']`;
    - the row with "sem mudança" is now `rows[1]`, and "+€ 90,00 / a mais" is on `rows[2]`.
  - **New tests:**
    - `reviewPage_orderToggleSwitchesToChange`:
      - Clicking "Maior mudança" gives `['Mercado', 'Restaurantes', 'Transporte', 'Casa']`.
      - Its `aria-pressed` is `true`.
    - `reviewPage_showsShareColumn`:
      - The columnheader is "% da saída".
      - The Casa row contains "37%", and the Transporte row contains "3%".
    - `reviewPage_budgetColumnShowsStatus`:
      - Budgets: Mercado limit 45000, spent 52000, EXCEEDED; Casa 95000 / 90000 NORMAL.
      - The Mercado row contains "€ 450,00" and "estourou".
      - The Casa row contains "€ 950,00" and "dentro do orçamento".
      - The Restaurantes row contains "—" and a button "Definir orçamento de Restaurantes".
      - The Mercado row has "Ajustar orçamento de Mercado".
    - `reviewPage_budgetsFollowMonth`:
      - Render `?month=2026-10`, then click the "‹" month button.
      - The budgets handler records the `month` param: `['2026-10', '2026-09']`.
    - `reviewPage_budgetsErrorKeepsReview`:
      - Budgets return a 500 problem.
      - All 4 row headers are still present, and "Não foi possível carregar os orçamentos." is shown.
    - `reviewPage_definirCreatesBudgetAndRefreshes`:
      - Click "Definir orçamento de Restaurantes".
      - The panel shows "Restaurantes", the limit is €120,00 and the text is "média dos últimos 3 meses".
      - Save. The POST body is `{ categoryId: 'c-rest', monthlyLimitCents: 12000 }`.
      - The handler then returns that budget (NORMAL). The panel closes, and the Restaurantes row shows "€ 120,00".
    - `reviewPage_ajustarStartsOnCurrentLimit`:
      - Click "Ajustar orçamento de Mercado".
      - The input is €450,00, and the button "Usar sugestão: € 430,00" exists.
    - `reviewPage_archivedCategoryHasNoAction`:
      - Transporte is `archived: true`, with a budget of 10000 NORMAL.
      - Its row shows "€ 100,00" and has no Definir/Ajustar button.
    - `reviewPage_firstMonthSuggestsMonthAmount`:
      - The response has `comparedMonths: 0`, `firstMonth: '2026-10'` and every `averageCents: null`.
      - There is no "Ordenar por" group.
      - Click "Definir orçamento de Restaurantes". The limit is €210,00 and the text is "gasto deste mês".

- [ ] **Step 2: Run them to see the new ones fail**

Run: `rtk vitest run src/features/review`
Expected: the new tests FAIL (no toggle, no share or budget column), and the updated order test FAILS.

- [ ] **Step 3: Implement `ReviewBudgetCell.vue`, the `ReviewCategories.vue` changes, the `ReviewPage.vue` wiring and the i18n keys as listed in Interfaces**
  - The suggestion label comes from `suggestedLimit(row).source`:
    - `'average'` gives `t('review.suggestionAverage', { n }, n)` with `n = data.comparedMonths`;
    - otherwise `t('review.suggestionMonth')`.

- [ ] **Step 4: Run the whole frontend suite, the type check and the lint**

Run: `rtk vitest run && rtk npx vue-tsc -b && rtk npm run lint`
Expected: all PASS.

- [ ] **Step 5: Check it in the running app**
  - Use the run skill or the project's dev setup to open `/review`.
  - Check desktop and a narrow viewport (≤ 400px). Columns and actions should be as in Layout, with no horizontal overflow on mobile.
  - Set a budget and see the Orçamentos page show it.

- [ ] **Step 6: Commit**

```bash
rtk git add src/features/review src/features/budgets/api src/shared/i18n
rtk git commit -m "feat(web): month review shows category share, current budgets and sets or adjusts them"
```
