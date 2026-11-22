# Month review: where the money went, against the usual

Status: approved by the user on 2026-11-22. Item 2 of the improvement roadmap. The month close (item 3) will later be added to this same screen.

## Intent

Once a month is over, the couple wants to understand **where the money went**. The question is "what was different this month from our usual?", not "how is the running month going". Today every screen shows one month at a time with no reference point. This breaks the "no number without context" rule from the dashboard-clarity rounds.

## Goals

- A new "Revisão" page. It shows a **closed** month's Entrou / Saiu / Sobrou and its spending per category, each next to the **average of the 3 previous months** and the difference.
- Spending categories are ordered by the size of the change, so what changed comes first.
- From a category row, one click reaches that month's transactions in that category.
- It is the home the month close (item 3) will later build on.

## Non-goals

- Comparing the running month. Partial months are excluded on purpose, because they always look cheaper.
- Changing the dashboard. It stays "the month now".
- Charts, trends over many months, income per category, or per-bank-account breakdowns.
- The month close itself (item 3).

## Anti-goals

- A misleading average. Months before the household started using the app must not count as zero spending.
- A second definition of "what counts as spending" that drifts from the dashboard's.

## Approaches

The user picked **A**.

- **A. A dedicated page with its own endpoint.**
  - One call returns the month's figures and the 3-month averages, computed on the server.
  - The dashboard keeps its role, and the month close has a home.
  - Cost: a new endpoint plus a new page.
- **B. Add averages to `/dashboard`.** Rejected:
  - On the running month it compares a partial month, which is the problem this design avoids.
  - It makes an endpoint that already runs 9 queries heavier.
  - Item 3 is left without a screen.
- **C. Frontend only: fetch `/dashboard` for 4 months and average in the browser.** Rejected:
  - It makes 4 heavy calls that compute balances, budgets and goals the review doesn't use.
  - It duplicates the spending rule in the browser.
  - Item 3 would need the backend anyway.

## Decided behavior

These were agreed with the user, who reviewed the proposal and approved it as written.

### Which months

- **Closed month:** a month is closed once it is over everywhere on Earth, consistent with `RunningMonths`. The last closed month is `RunningMonths.earliest(clock).minusMonths(1)`.
- **Default and limits:**
  - The page opens on the last closed month.
  - "›" stops there; the running month is never reviewable.
  - "‹" goes back to the household's **first month**, which is the month of its earliest transaction of any kind.
- **No transactions at all:** the page shows an empty state with no figures and no month navigation.

### The average

- **Window:** the 3 months before the reviewed month, counting only months on or after the first month.
- **Fewer than 3 months:**
  - With 1 or 2 qualifying months, the average uses those, and the label says so: "média de 2 meses" / "média de 1 mês".
  - With none (the reviewed month is the first month), the comparison and difference columns are hidden.
- **What is averaged:** the sum over the qualifying months divided by their count, so a month with no spending in a category counts as 0. Amounts are in cents, rounded half-up to a whole cent.

### Figures

- **Totals:** Entrou, Saiu and Sobrou for the month, each with its average and its difference.
  - Sobrou = Entrou − Saiu, the same as the dashboard's month summary.
  - It may be negative. The dashboard labels that case "Faltou"; reuse that wording.
  - Its average is the average Entrou minus the average Saiu, so the three figures always add up despite rounding. This is my call.
- **Categories:**
  - Every expense category with spending in the reviewed month or in any month of the window appears.
  - A category that dropped to zero shows €0 with a negative difference.
  - Archived categories appear too, because the history belongs to them.
- **Order:** by absolute difference, largest first, then by the month's amount (descending), then by name.
  - With no comparison (first month), order by the month's amount (descending), then by name.
- **What counts:** the same rule as the dashboard, with only `INCOME` and `EXPENSE` kinds and transfers excluded.
  - Every expense has a category; the DB constraint guarantees it, so there is no "uncategorised" line.
  - Reuse or share the existing queries' rule rather than restating it differently.

### Difference display

- **Colour by meaning, not by sign:**
  - Spending more, or earning less, is shown in the expense colour.
  - Spending less, or earning more, is shown in the income colour.
  - For Sobrou, more is good.
  - Use the existing design tokens (`text-expense` / `text-income`), which the design guard allows.
- **Direction:** a ▲ or ▼ marker plus the signed amount (for example "+€90"). Colour is never the only signal, so the marker and sign carry it for screen readers and colour-blind users. The marker icons are `aria-hidden`, with a text alternative.
- **Small differences:** a difference under €1 in absolute value shows "—" in the muted colour.

### Navigation

- **Route:** `/review?month=YYYY-MM`.
  - The URL holds the month, as on the dashboard.
  - A missing, malformed or out-of-range month in the URL falls back to, or is clamped into, `[first month, last closed month]`.
- **Menu:**
  - The sidebar gets the "Revisão" item right after Dashboard, in `DAILY_ITEMS`.
  - On mobile the bottom nav has no room, so it also goes in `MORE_ITEMS` (the user menu), next to Objetivos. This is my call.
- **Month selector:** reuse the existing `MonthSelector`, with new optional `min` / `max` props that disable "‹" / "›" at the limits. The other pages using it are unaffected.
- **Category rows:** each row links to `/transactions` with that category and the month's range, as `SpendingDonut` does (`monthRange(month)` plus `categoryId`).

## Build outline

The builder plans the details.

- **Backend:** a new `review` feature package (controller, service, query, DTO), following the `dashboard` package's structure.
  - **Endpoint:** `GET /api/v1/month-review?month=YYYY-MM`.
    - `month` is optional and defaults to the last closed month.
    - A month after the last closed month is a 400 with a new error code, such as `MONTH_NOT_CLOSED`, which gets a pt-BR and en message.
    - A month before the first month is not an error: it returns zeros and no comparison.
  - **Response, roughly:**
    - `month`, `currency`
    - `firstMonth` (nullable when there are no transactions), `lastClosedMonth`
    - `comparedMonths` (0–3)
    - `income`, `expense` and `left`, each holding `{ cents, averageCents | null }`
    - `categories[]` with `{ categoryId, name, color, icon, archived, cents, averageCents | null }`
    - Differences are left to the client, which has both numbers. The builder may add them server-side if that reads better.
  - **Queries:** totals and per-category spending grouped by month over the 4-month span, in one or two queries. The earliest transaction date is a single `MIN(date)`.
  - **Scope:** scope every query to the household (`account_id`), like the existing queries. Add a statement-count test in the style of `DashboardControllerTest`.
- **Frontend:**
  - **Feature folder:** a new `features/review/` with its page, its API query (TanStack Vue Query) and a pure `lib/` for ordering and difference formatting, with unit tests.
  - **i18n:** keys in `pt-BR.json` and `en.json`.
  - **API types:** regenerate with `npm run api:types`.

## Left to the builder

- The exact DTO field names and the query shape.
- The layout details beyond the approved sketch:
  - a summary row of 3 figures;
  - a category table with columns month / average / difference;
  - mobile stacking.
- The loading, error and empty states, following the dashboard's patterns (`Skeleton`, `CardEmpty`).
- The wording of new strings, matching the existing tone ("Entrou / Saiu / Sobrou").

## Approved sketch

```
Revisão de outubro 2026                    ‹ setembro   novembro ›
Entrou €3.200 (=)   Saiu €2.450 (+€310)   Sobrou €750 (−€310)

Para onde foi                       outubro   média 3 meses  diferença
Mercado                               €520       €430        +€90  ▲
Restaurantes                          €210       €120        +€90  ▲
Casa                                  €900       €900          —
Transporte                             €80       €140        −€60  ▼
```
