# Clarity round B: the other pages — design

**Date:** 2026-11-19 · **Size:** project (one backend endpoint, four pages, one small header)

## Intent and why

Round A ([dashboard clarity](2026-11-19-dashboard-clarity-design.md), PR #18) set the rule: **every number answers "is this a lot or a little?"** Round B applies it to the other pages. The visual identity and each page's layout stay. Only context is added: totals, what is left, monthly equivalents.

## Decisions taken with the user

- **Scope:**
  - Lançamentos gets period totals;
  - Orçamentos gets a month summary;
  - Contas gets the total balance;
  - Recorrências gets the fixed monthly commitment and per-rule monthly equivalents;
  - Objetivos gets a one-line total;
  - Categorias stays unchanged (it is a set-up page).
- **The Lançamentos totals come from the backend in this round**, because the list is paginated by 50 and the front cannot sum the whole period.
- **Transfers:**
  - without a bank-account filter, transfers stay out of the totals, as in today's day net;
  - with a bank-account filter, a transfer **into** that account counts as income and one **out of** it counts as expense.
- **Approach A:**
  - one branch, `feature/clarity-round-b`, stacked on `feature/dashboard-clarity`, with backend and frontend together;
  - the PR targets `develop` once #18 is merged; until then its base is `feature/dashboard-clarity`;
  - rejected alternatives: B, two PRs (an unused endpoint and two review cycles); C, waiting for #18 (idle time).
- **A separate endpoint**, `GET /transactions/totals`, takes the same filter parameters as the list. Rejected alternative: totals inside the paginated response, because the sum would be recomputed on every page and the list's shape would change.

## Goals

### 1. Endpoint `GET /api/v1/transactions/totals`

**Parameters:** exactly the list's filters, `from`, `to`, `kind`, `categoryId`, `bankAccountId`, `createdBy` and `q`, with the same validation and errors:
- `from > to` gives `from: OutOfRange`;
- `q` longer than 100 gives `q: Size`;
- a category or bank account that is not owned gives the same error as the list.

**Response:** `TransactionTotalsResponse { incomeCents: long, expenseCents: long, netCents: long, count: long }`.
- `count` is the number of transactions matched, the same number as the list's `totalElements`. Transfers are included in it.
- `netCents` = income − expense.

**Rules:**
- **Without `bankAccountId`:**
  - income is the sum of `INCOME` transactions and expense the sum of `EXPENSE` transactions;
  - `TRANSFER` adds nothing to either.
- **With `bankAccountId` = X:**
  - `INCOME` and `EXPENSE` on X count as above;
  - a `TRANSFER` whose destination is X adds to income;
  - a `TRANSFER` whose origin is X adds to expense.
- **With `kind=TRANSFER` and no account filter:** income and expense are 0 and `count` still counts. The UI handles this case (see 2).

The predicates are built once and shared with `list`, so the two can never disagree on which rows match. The sum is a single aggregate query, never a load of the rows. Account scoping (`inAccount`) is mandatory, as in `list`.

### 2. Lançamentos page

- A **totals strip** sits between the filters and the list. It shows **Entrou** (income color), **Saiu** (expense color), and **Saldo do período** (`netCents`, signed, in the expense color when negative). Under the balance: "{count} lançamentos" with a plural form.
- The strip follows the active filters. The query key includes the filter, so it refetches when the filter changes, and transaction mutations invalidate it as they invalidate the list.
- **With `kind=TRANSFER` and no account filter**, the strip shows only the count line ("{count} transferências"), because income and expense are always 0 there.
- While the totals load, three skeletons show. On error, the strip shows the translated error inline and the list stays usable.
- When the list is empty, the strip is hidden and the empty state shows as today.
- **Day net:** with a bank-account filter, a day's net applies the same transfer rule (in to X +, out of X −). Without one, it is unchanged. The sum of the visible days' nets then matches the strip for a fully loaded period.

### 3. Orçamentos page (month summary)

The summary goes above the list, below the month selector, and only when there are budgets. It shows:
- "**{spent}** gastos de {budgeted} orçados", with the sum of spent and the sum of limits over the month's budgets;
- one bar with the overall percent, colored as `BudgetProgressBar` would color it for that percent: normal under the warning threshold, warning, or exceeded. The builder reuses `BudgetProgressBar` or its color mapping; it does not invent a new threshold;
- "restam {amount} no total" or "passou {amount} no total";
- "{n} estourado(s)", only when n > 0: the count of budgets with status `EXCEEDED`;
- "faltam N dias" or "último dia do mês", only for the current month. This is the same rule as the dashboard.

Each budget row adds "restam X" or "passou X", with the dashboard's colors:
- expense color when over or `EXCEEDED`;
- warning color when `WARNING`;
- otherwise the foreground color.

### 4. Contas page (total balance)

- The total sits above the list: "Saldo total" with the sum of the **active** (non-archived) accounts' balances, and "em {n} contas ativas" in muted text, with a plural form.
- It is shown only when there is at least one active account.
- It is unchanged by the "show archived" switch; archived accounts never count.

### 5. Recorrências page (fixed monthly commitment)

The summary goes above the list. It shows **Entradas fixas**, **Saídas fixas** and **Sobra fixa por mês**.
- The figures are the monthly equivalents of **active, unfinished** rules (the page's `activeRules`), with `TRANSFER` rules excluded.
- When the outflows exceed the inflows, the third figure reads **Falta fixa** in the expense color, mirroring "Faltou".
- **Monthly equivalent:** MONTHLY × 1; WEEKLY × 52 ÷ 12; YEARLY ÷ 12. Rounding to cents happens once, at display.
- Each WEEKLY or YEARLY row shows, under its amount, "≈ {amount}/mês" in muted text. MONTHLY rows and transfers show nothing extra.
- The summary shows only when there is at least one active rule.

### 6. Objetivos page

- The header shows one line under the salary picker: "{amount} guardados em {n} objetivos" (plural form), using `totalSavedCents` and the number of active goals.
- It shows only when there is at least one active goal.

## Non-goals

- No change to Categorias, colors, fonts or tokens.
- No month-over-month comparison.
- No change to the dashboard (done in round A).

## Anti-goals

- Totals that disagree with the list for the same filter.
- Transfers inflating "Entrou" and "Saiu" when no account is filtered.
- A summary that counts archived accounts, finished rules or transfer rules.

## Constraints

- Shared pure functions move to `frontend/src/shared/lib`:
  - `daysLeftInMonth` and `budgetRemainder` from `features/dashboard/lib/figures.ts` (round A);
  - a new `monthlyEquivalent`.
- The dashboard imports them from shared. No cross-feature imports (the architecture guard).
- Money goes through `MoneyText`. Plain-string money uses a lib helper, never `formatMoney` in views (the design guard).
- All copy goes in pt-BR and en, with plural forms where a count appears.
- Backend:
  - an integration test for the endpoint covers the transfer rules, account scoping, validation errors and agreement with `list` for the same filter;
  - the OpenAPI schema and `frontend/src/shared/api/schema.ts` are regenerated, or the type is hand-added consistently with how the repo does it.

## Layout sketches

```
Lançamentos  [filters]
[ Entrou € 0,00 | Saiu € 980,00 | Saldo do período −€ 980,00 · 14 lançamentos ]
[day groups …]

Orçamentos   [month]
[ € 1.617,00 gastos de € 1.700,00 orçados          faltam 11 dias ]
[ ███████████████████░ ]   restam € 83,00 no total · 1 estourado
[rows … each with "restam/passou X"]

Contas       [ Saldo total € 8.420,00 · em 3 contas ativas ]
Recorrências [ Entradas fixas | Saídas fixas | Sobra fixa por mês ]   rows: "≈ € 173,33/mês"
Objetivos    € 1.500,00 guardados em 2 objetivos
```

## Calls made during review (not discussed with the user)

- **Day net with an account filter** follows the totals' transfer rule, so the days and the strip agree. Proposed in the conversation; the user did not object.
- **`kind=TRANSFER` without an account filter** shows only the count.
- **`count`** includes transfers. It is "how many rows this filter matches".
- **The totals request fails on its own:** the strip shows an inline error and the list is unaffected.
- **The overall budget bar** reuses the existing status thresholds rather than a new one.
- **Recorrências:** when outflows exceed inflows, the third figure reads "Falta fixa".

## Left to the builder

- Whether the totals query uses a Criteria `CriteriaQuery<Tuple>` with `CASE` sums or JPQL, as long as it shares the filter predicates with `list`.
- Exact i18n keys, and the component split (for example a `TransactionTotals.vue` and a `BudgetsSummary.vue`).
- Whether the period strip reuses the dashboard's `MonthSummaryCard` markup through a shared component, or has its own.
