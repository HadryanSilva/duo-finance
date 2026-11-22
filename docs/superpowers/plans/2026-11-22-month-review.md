# Month Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** add a "Revisão" page that compares a closed month's totals and per-category spending with the average of the 3 previous months.

**Architecture:** the backend adds a `review` package with `GET /api/v1/month-review`, built on 3 fixed queries (meta, monthly totals, monthly spending per category with its details). The frontend adds `features/review/`, which has a pure `lib/` for averages, labels and ordering, a TanStack query and the page. The shared `MonthSelector` gains `min`/`max`.

**Tech Stack:**
- Backend: Spring Boot 4, Java 25, NamedParameterJdbcTemplate, Postgres 16, Testcontainers (`@IntegrationTest`).
- Frontend: Vue 3, vue-i18n, TanStack Vue Query, Vitest + MSW, @lucide/vue.

**Spec:** `docs/superpowers/specs/2026-11-22-month-review-design.md`

## Global Constraints

- **Closed months only.** The last closed month is `RunningMonths.earliest(clock).minusMonths(1)`, and a later month is rejected.
- **First month** is the month of the household's earliest transaction of any kind. If there are no transactions, it is `null`.
- **Average.**
  - The window is the 3 months before the reviewed month, counting only months `>=` first month.
  - It is the sum divided by the qualifying-month count. A month without spending counts as 0.
  - Rounding is half-up to a whole cent.
  - With 0 qualifying months, `averageCents` is `null` everywhere.
- **Sobrou average** = average Entrou − average Saiu.
- **What counts:** only `INCOME`/`EXPENSE` kinds, never `TRANSFER`. Every expense has a category.
- **Which categories appear:** every expense category with spending in the month or in the window, archived ones included.
- **Order:** by `|difference|` descending, then the month's cents descending, then name (case-insensitive). With no comparison, by the month's cents descending, then name.
- **Colour by meaning.**
  - Worse (more spent, less earned, less left) is `text-expense`; better is `text-income`.
  - An `|difference|` under 100 cents shows "—" in `text-muted-foreground`.
  - The ▲/▼ icons are `aria-hidden`, and the sign plus the text carry the meaning.
- **Route and menu.**
  - The route is `/review?month=YYYY-MM`. An invalid or out-of-range month is clamped into `[firstMonth, lastClosedMonth]`.
  - The nav item is "Revisão", right after Dashboard in `DAILY_ITEMS`, and also in `MORE_ITEMS`.
- **Repo conventions.**
  - Prefix commands with `rtk`.
  - The checkout uses CRLF: run `prettier --check --end-of-line auto` on touched files only, and Spotless on touched Java files only.
  - Commits end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **Month boundaries at the UTC edge.** At `2027-03-01T05:00:00Z` it is still February at UTC-12, so the last closed month must be January. Covered by `review_lastClosedMonthFollowsRunningMonths`.
2. **A household that started mid-window.** If the first month is 2027-01 and the reviewed month is 2027-02, the average is 1 month and the label says "média de 1 mês". Before-app months must not count as zeros. Covered by `review_averageOnlyCountsMonthsSinceFirst`.
3. **A category present only in the window,** or archived since. It must still appear with €0 for the month and a negative difference. Covered by `review_categoryOnlyInWindowAppearsWithZero`.
4. **A URL month outside the range** (`?month=2030-01`, `?month=garbage`, or before the first month). The page clamps it and never shows the API's 400 to the user. Covered by `reviewPage_clampsMonthFromUrl`.
5. **Rounding.** 3 months of 100, 100 and 101 cents average to 100, and 100, 101 and 101 average to 101. The Sobrou average must equal Entrou avg − Saiu avg, not a separately rounded value. Covered by `review_averageRoundsHalfUp`.

---

### Task 1: Backend endpoint `GET /api/v1/month-review`

**Files:**
- Create: `backend/src/main/java/br/dev/duofinance/review/controller/MonthReviewController.java`
- Create: `backend/src/main/java/br/dev/duofinance/review/service/MonthReviewService.java`
- Create: `backend/src/main/java/br/dev/duofinance/review/repository/MonthReviewQuery.java`
- Create: `backend/src/main/java/br/dev/duofinance/review/dto/MonthReviewResponse.java`
- Modify: `backend/src/main/java/br/dev/duofinance/shared/error/ErrorCode.java`, adding `MONTH_NOT_CLOSED(HttpStatus.BAD_REQUEST)`
- Test: `backend/src/test/java/br/dev/duofinance/review/controller/MonthReviewControllerTest.java`

**Interfaces:**
- Produces (the JSON the frontend consumes):

```java
public record MonthReviewResponse(
        YearMonth month,
        String currency,
        @Nullable YearMonth firstMonth,
        YearMonth lastClosedMonth,
        int comparedMonths,          // 0..3
        Figure income,
        Figure expense,
        Figure left,                 // cents = income - expense; averageCents = income avg - expense avg
        List<CategoryLine> categories) {
    public record Figure(long cents, @Nullable Long averageCents) {}
    public record CategoryLine(
            UUID categoryId, String name, String color, String icon, boolean archived,
            long cents, @Nullable Long averageCents) {}
}
```

- **Category order:** `categories` is ordered by name. The frontend sorts it (Task 3).
- **Controller:** `@RequestParam(required = false) @DateTimeFormat(pattern = "yyyy-MM") YearMonth month`. It defaults to the last closed month, the same way as `DashboardController`.
- **Rejection:** a month after the last closed month throws `new ApiException(ErrorCode.MONTH_NOT_CLOSED)`.

**Queries** (`MonthReviewQuery`, each scoped by `account_id = :accountId`):
1. `meta(accountId)`: `accounts.currency` plus `(SELECT MIN(date) FROM transactions WHERE account_id = a.id)`.
2. `monthlyTotals(accountId, from, to)`: returns `Map<YearMonth, long[]{income, expense}>`.
   - `date_trunc('month', date)::date`, `kind IN ('INCOME','EXPENSE')`.
   - The date span is `windowStart.atDay(1)` to `month.atEndOfMonth()`.
3. `monthlySpending(accountId, from, to)`: expense rows, `JOIN categories c ON c.id = t.category_id`.
   - Grouped by month, then by `c.id, c.name, c.color, c.icon, c.archived_at IS NOT NULL`.

The service runs these 3 statements and no others.

**Service logic:** `windowStart = max(month - 3, firstMonth)` and `comparedMonths = monthsBetween(windowStart, month)` clamped to `[0, 3]`. When `firstMonth == null` or `month < firstMonth`, `comparedMonths` is 0.

Rounding is half-up: `average(sum, n) = (2 * sum + n) / (2 * n)`. The sums are non-negative.

Test setup copies `DashboardControllerTest`: the `@IntegrationTest`, `MutableClock`, `tx`/`transfer`/`seeded` helpers and the statement-counting `@TestConfiguration`. Set the clock to `2027-03-15T10:00:00Z` in `@BeforeEach`, so the last closed month is 2027-02.

- [ ] **Step 1: Write the failing tests**
  - `review_defaultsToLastClosedMonth`: with no `month` param, `month == "2027-02"` and `lastClosedMonth == "2027-02"`.
  - `review_lastClosedMonthFollowsRunningMonths`: at `2027-03-01T05:00:00Z`, `lastClosedMonth == "2027-01"`, and `?month=2027-02` returns 400 with code `MONTH_NOT_CLOSED`.
  - `review_runningMonth_isRejected`: `?month=2027-03` returns 400 `MONTH_NOT_CLOSED`.
  - `review_noTransactions_hasNoFirstMonthAndNoComparison`:
    - `firstMonth` is null;
    - `comparedMonths == 0`;
    - `income.cents == 0` and `income.averageCents` is null;
    - `categories` is empty.
  - `review_threeMonthAverage_totalsAndCategories`:
    - Data:
      - Expenses in one category: 2026-11 = 300, 2026-12 = 600, 2027-01 = 900, 2027-02 = 1000.
      - Income of 5000 in every one of those months.
    - Expected for `?month=2027-02`:
      - `comparedMonths == 3`;
      - `expense` is `{1000, 600}`, `income` is `{5000, 5000}` and `left` is `{4000, 4400}`;
      - the category line is `{cents: 1000, averageCents: 600}`.
  - `review_averageOnlyCountsMonthsSinceFirst`: the first transaction is in 2027-01 (expense 400), and 2027-02 has expense 100. Expect `comparedMonths == 1` and `expense.averageCents == 400`.
  - `review_firstMonth_hasNoComparison`: `?month=` equal to the first month gives `comparedMonths == 0` and every `averageCents` null.
  - `review_monthWithoutSpendingCountsAsZero`:
    - Data: the category is spent 900 in 2026-11 only, and the first month is 2026-11 (with income there).
    - Expected for `?month=2027-02`: the line is `{cents: 0, averageCents: 300}`.
    - This also covers `review_categoryOnlyInWindowAppearsWithZero`: archive that category via `UPDATE categories SET archived_at = now()` and expect `archived == true`.
  - `review_averageRoundsHalfUp`:
    - Data: expenses 100, 100, 101 in the window give an average of 100. A second category with 100, 101, 101 gives 101.
    - Expected: `left.averageCents == income.averageCents - expense.averageCents`.
  - `review_excludesTransfers`: a transfer in 2027-02 does not change `income` or `expense`.
  - `review_isScopedToHousehold`: another user's transactions do not appear.
  - `review_queryCountIsFixed`: `AUTH_STATEMENTS + 3` statements with 2 categories, and again with 6.

- [ ] **Step 2: Run them and verify they fail**

  Run: `rtk ./mvnw -q test -Dtest=MonthReviewControllerTest` from `backend/`. Docker Desktop must be running.

  Expected: FAIL. The tests compile only after the DTO exists, so first create empty stubs of the response record and the controller returning `null`, and run again. The expectation is 404 or 500 versus the asserted values.

- [ ] **Step 3: Implement the DTO, query, service and controller, and add `MONTH_NOT_CLOSED` to `ErrorCode`.**

- [ ] **Step 4: Run the tests and verify they pass**

  Run: `rtk ./mvnw -q test -Dtest=MonthReviewControllerTest`, then the full `rtk ./mvnw verify`.

  Expected: all pass. The full suite is about 620 tests, and BUILD SUCCESS.

- [ ] **Step 5: Spotless on the touched files, then commit**

```bash
rtk git add backend/src/main/java/br/dev/duofinance/review backend/src/main/java/br/dev/duofinance/shared/error/ErrorCode.java backend/src/test/java/br/dev/duofinance/review
rtk git commit -m "feat(api): month review compares a closed month with the 3-month average"
```

### Task 2: `MonthSelector` gains `min`/`max`

**Files:**
- Modify: `frontend/src/shared/ui/MonthSelector.vue`
- Test: `frontend/src/shared/ui/MonthSelector.spec.ts`

**Interfaces:**
- Produces: `<MonthSelector v-model="month" :min="'2026-11'" :max="'2027-02'" />`. Both props are optional `YYYY-MM` strings.
- Behavior: "‹" is `disabled` when `model <= min`, and "›" is `disabled` when `model >= max`. Without the props the component behaves as today.

- [ ] **Step 1: Write the failing tests**
  - `monthSelector_atMax_disablesNext`: with `modelValue: '2027-02', max: '2027-02'`, the button named "Próximo mês" (the `monthSelector.next` text) is disabled and "previous" is enabled.
  - `monthSelector_atMin_disablesPrevious`: the mirror case.
  - `monthSelector_withoutLimits_bothEnabled`.

- [ ] **Step 2: Run them and verify they fail**

  Run: `rtk vitest run src/shared/ui/MonthSelector.spec.ts` from `frontend/`.

  Expected: the 2 limit tests FAIL (the button is not disabled).

- [ ] **Step 3: Implement.** `YYYY-MM` strings compare correctly with `<=` and `>=`.

- [ ] **Step 4: Run the tests and verify they pass**

  Run: same command. Expected: PASS.

- [ ] **Step 5: Commit**

  `feat(web): month selector can stop at a first and last month`

### Task 3: Review figures lib

**Files:**
- Create: `frontend/src/features/review/lib/review.ts`
- Test: `frontend/src/features/review/lib/review.spec.ts`

**Interfaces:**
- Consumes: `MonthReviewResponse`, the type regenerated in Task 4. To keep this task independent, Step 0 regenerates `schema.ts` first: start the backend, or hand-add the types matching Task 1's record. Then export `MonthReviewResponse` from `@/shared/api/types` the way `DashboardResponse` is exported.
- Produces:

```ts
export const SMALL_DIFFERENCE_CENTS = 100
/** 'worse' | 'better' | 'same' for a figure where more is good (income, left) or bad (expense). */
export type Tone = 'worse' | 'better' | 'same'
export function differenceTone(cents: number, averageCents: number | null, moreIsGood: boolean): Tone | null // null when no average
export interface ReviewRow { categoryId: string; name: string; color: string; icon: string; archived: boolean; cents: number; averageCents: number | null; differenceCents: number | null; tone: Tone | null }
export function reviewRows(categories: MonthReviewResponse['categories']): ReviewRow[] // sorted per Global Constraints
export function clampMonth(wanted: unknown, firstMonth: string | null, lastClosedMonth: string): string
```

- **Tone:** `same` when `|cents − average| < 100`. Otherwise an expense above the average is `worse`, and income or left above the average is `better`.
- **`clampMonth`:**
  - A non-string or non-`YYYY-MM` value gives `lastClosedMonth`.
  - A value below `firstMonth` gives `firstMonth`.
  - A value above `lastClosedMonth` gives `lastClosedMonth`.
  - With `firstMonth == null`, the result is `lastClosedMonth`.

- [ ] **Step 1: Write the failing tests**
  - `differenceTone`:
    - `(1000, 600, false)` is `'worse'`;
    - `(500, 600, false)` is `'better'`;
    - `(650, 600, true)` is `'same'`, because 50 is under 100;
    - `(700, 600, true)` is `'better'`;
    - `(700, null, true)` is `null`.
  - `reviewRows`:
    - sorts `[D: 80/140, C: 900/900, B: 210/120, A: 520/430]` into **A, B, D, C**. A and B tie at a difference of 90, and A's 520 is more than B's 210.
    - With `averageCents: null` everywhere, it sorts by cents descending, then name.
    - Equal cents sort by name, case-insensitive.
  - `clampMonth`:
    - `('garbage', '2026-11', '2027-02')` is `'2027-02'`;
    - `('2030-01', …)` is `'2027-02'`;
    - `('2026-01', '2026-11', '2027-02')` is `'2026-11'`;
    - `('2026-12', …)` is `'2026-12'`;
    - `(['2026-12'], …)` is `'2027-02'`;
    - `('2026-12', null, '2027-02')` is `'2027-02'`.

- [ ] **Step 2: Run them and verify they fail**

  Run: `rtk vitest run src/features/review/lib/review.spec.ts`

  Expected: FAIL (the module is not found).

- [ ] **Step 3: Implement.**

- [ ] **Step 4: Run the tests and verify they pass.** Expected: PASS.

- [ ] **Step 5: Commit**

  `feat(web): month review figures, ordering and month clamping`

### Task 4: Review page, route, nav and i18n

**Files:**
- Create: `frontend/src/features/review/api/review-api.ts`, with `useMonthReview(month: MaybeRefOrGetter<string | undefined>)` and query key `['month-review', month]`. Omit the `month` param when it is undefined.
- Create: `frontend/src/features/review/pages/ReviewPage.vue`
- Create: `frontend/src/features/review/components/ReviewSummary.vue`, which shows the 3 figures with their average and difference.
- Create: `frontend/src/features/review/components/ReviewCategories.vue`, the category table.
- Create: `frontend/src/features/review/components/DifferenceText.vue`, which shows ▲/▼ plus the signed `MoneyText` in tone colour, or "—".
- Test: `frontend/src/features/review/pages/ReviewPage.spec.ts`
- Modify:
  - `frontend/src/app/router.ts`: route `/review`, the same meta as `/dashboard`.
  - `frontend/src/app/components/nav.ts`: `REVIEW = item('/review', 'nav.review', CalendarCheck)`, placed in `DAILY_ITEMS` after `DASHBOARD` and in `MORE_ITEMS`.
  - `frontend/src/shared/i18n/locales/pt-BR.json` and `en.json`: `nav.review`, `review.*`, and `errors.MONTH_NOT_CLOSED`.
  - `frontend/src/shared/i18n/error-codes.ts`: add `'MONTH_NOT_CLOSED'`.
  - `frontend/src/shared/api/schema.ts`: regenerate with `npm run api:types` while the backend runs. Hand-editing is acceptable if the output matches what the generator emits; CI diffs it.

**Interfaces:**
- Consumes: Task 1's JSON, Task 2's `min`/`max`, and Task 3's `reviewRows`, `differenceTone` and `clampMonth`.
- **Month flow:**
  1. On the first load without a valid `?month`, call the API with no month. The server's default is the last closed month.
  2. Once the response arrives, `month = clampMonth(route.query.month, data.firstMonth, data.lastClosedMonth)`.
  3. If that differs from the URL, `router.replace` with it.
  4. Changing the month via `MonthSelector` (`:min="data.firstMonth"`, `:max="data.lastClosedMonth"`) updates the URL query, like `DashboardPage`.
- **Copy** (pt-BR / en):
  - **Title:** `review.title` = "Revisão do mês" / "Month review".
  - **Subtitle:** `review.subtitle` = "Para onde o dinheiro foi, comparado com a média." / "Where the money went, compared with the average."
  - **Average column header**, `review.average`, pluralized:
    - pt-BR: "média de {n} mês" for 1 and "média de {n} meses" otherwise;
    - en: "{n}-month average".
  - **Section heading:** `review.whereItWent` = "Para onde foi" / "Where it went".
  - **Difference column header:** `review.difference` = "Diferença" / "Difference".
  - **Empty state:** `review.empty` = "Ainda não há lançamentos para revisar." / "No transactions to review yet."
  - **Screen-reader text for the markers:** `review.more` "a mais" / "more" and `review.less` "a menos" / "less".
  - **Error:** `errors.MONTH_NOT_CLOSED` = "Esse mês ainda não terminou." / "That month is not over yet."
  - **Reused keys:** the summary labels come from `dashboard.summary.in/out/left/short`.
- **Category rows:** link to `{ path: '/transactions', query: { ...monthRange(month), categoryId } }`, as in `SpendingDonut`. Archived categories still link.
- **Empty and hidden states:**
  - With `firstMonth == null`, show `CardEmpty` with `review.empty`, with no selector and no table.
  - With `comparedMonths == 0`, hide the average and difference columns.

- [ ] **Step 1: Write the failing page tests** (MSW handlers on `GET /api/v1/month-review`, following `DashboardPage.spec.ts`)
  - `reviewPage_showsSummaryAndCategoriesOrdered`:
    - The fixture is the sketch's 4 categories with `comparedMonths: 3`.
    - Expected:
      - the row order is Mercado, Restaurantes, Transporte, Casa;
      - the header is "média de 3 meses";
      - "+€ 90" is visible;
      - Casa shows "—".
  - `reviewPage_categoryLinksToTransactions`: the Mercado link's href contains `categoryId=<id>` and the month's `from` and `to`.
  - `reviewPage_clampsMonthFromUrl`: render at `/review?month=2030-01`. The handler returns `lastClosedMonth: '2027-02'`. The URL ends up at `month=2027-02`, and no request was sent with `month=2030-01`.
  - `reviewPage_atLastClosedMonth_nextDisabled`: "›" is disabled.
  - `reviewPage_firstMonth_hidesComparison`: with `comparedMonths: 0`, there is no "média" header and no difference column.
  - `reviewPage_noTransactions_showsEmpty`: with `firstMonth: null`, the empty text is shown and there is no `MonthSelector`.
  - `nav_includesReviewAfterDashboard`: in an existing nav or sidebar spec, or in a new `nav.spec.ts`, `DAILY_ITEMS[1].to === '/review'`.

- [ ] **Step 2: Run them and verify they fail**

  Run: `rtk vitest run src/features/review src/app`

  Expected: FAIL (the page is missing).

- [ ] **Step 3: Implement** the api, components, page, route, nav, i18n and error code.

- [ ] **Step 4: Run everything and verify it passes**

  Run, from `frontend/`:
  - `rtk vitest run`
  - `rtk npx vue-tsc --noEmit -p tsconfig.app.json`
  - `rtk lint`
  - `rtk prettier --check --end-of-line auto` on the touched files

  Expected:
  - all tests pass, including the i18n key-parity spec and `src/test/design-usage.spec.ts`;
  - the type check, lint and prettier are clean.

- [ ] **Step 5: Commit**

  `feat(web): month review page`
