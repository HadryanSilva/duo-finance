# Dashboard clarity and navigation — design

**Date:** 2026-11-19 · **Size:** project, frontend only (dashboard rework, goal suggestions moved to the Goals page, sidebar regrouped)

## Intent and why

The user finds the app hard to read. It shows numbers without the context that gives them meaning. Their example: the "Gastos por categoria" card lists each category's amount under the donut but not its share of the month, so "is this a lot?" has no answer on screen. The same pattern shows up in other places:
- the budgets card shows a bar but not what is left;
- the dashboard is a grid of seven cards of equal weight with no order of reading;
- the sidebar puts eight items on one level, so set-up pages weigh as much as daily ones.

The aim is **clarity and organization**. The visual identity stays as it is: indigo `#4F46E5` and Inter, green and red only for income and expense, and every token in `frontend/src/app/styles.css`. See [the MVP spec](2026-11-05-duo-finance-mvp-design.md).

This is round A, covering the dashboard and the navigation. A later round B will apply the same rule ("every number answers *is this a lot or a little?*") to the other pages: Transactions, Budgets, Goals and so on.

## Decisions taken with the user

- **Start with the dashboard and the navigation (approach A).** Rejected alternatives:
  - **B, a sweep of every page that adds context without rearranging layouts:** it leaves "organization" unsolved. It is deferred as round B.
  - **C, both at once:** the largest PR, with nothing shipped until the end.
- **Layout "summary on top, blocks in a grid" (mockup option A).** Rejected alternative: a main column with a side rail (option B).
- **The visual identity does not change.** No new tokens, colors or fonts.
- **Goal suggestions (Confirmar / Ajustar / Pular) move to the Goals page.** The dashboard keeps one reminder line when contributions are pending.
- **Sidebar groups:**
  - daily use: Dashboard, Transações, Orçamentos, Objetivos;
  - **"Cadastros":** Contas bancárias, Categorias, Recorrentes (the user configures recurring rules once);
  - Configurações pinned to the bottom.

## Goals

### 1. Month summary (new, first block under the month selector)

- It shows three figures: **Entrou** (`incomeCents`, income color), **Saiu** (`expenseCents`, expense color), and **Sobrou** = income − expense.
- Under Sobrou: "X% do que entrou", rounded to a whole percent, only when income > 0.
- When expense > income, the third figure reads **Faltou** with the absolute difference, in the expense color, and the line under it is not shown.
- The figures sit side by side on desktop and stack on mobile.

### 2. "Para onde foi o dinheiro" (renamed from "Gastos por categoria")

- The donut stays.
- Each legend row shows color dot, name, amount, and the **share of the month's total spending**:
  - the share is a whole percent of the sum of `spendingByCategory`;
  - a share under 1% shows "<1%".
- The backend already sorts the rows by amount, largest first. The UI keeps that order.
- From the 6th category on, the rest collapse into one row, **"Outras N"**, with their summed amount and share. The donut uses the same grouping.
- **Each named category row is a link** to `/transactions?from=<first day>&to=<last day>&categoryId=<id>` for the selected month. The "Outras N" row is not a link. The Transactions page already reads these query keys (`filterFromQuery`).

### 3. Budgets

- The card title is followed by "faltam N dias", but only when the selected month is the current month. N counts today, from the client's local date.
- Each row shows:
  - category name and the bar (`BudgetProgressBar`, unchanged);
  - "restam X €" when spent ≤ limit, or "passou X €" in the expense color when spent > limit;
  - a small muted line "<spent> de <limit>".
- An "at warning" row (status from the API) uses the warning color for its "restam" text.

### 4. Support row: Saldo, Próximos 30 dias, Objetivos

- **Saldo:**
  - total balance, the "as of" date and the per-account list stay;
  - saldo livre and the amount in goals are shown under the total as one compact line, with the same rules as today: shown only when free balance exists and goals hold money, and the negative case stays in the expense color.
- **Próximos 30 dias:**
  - the first 5 occurrences, as today's rows;
  - when there are more, a "Ver todos" link to `/recurring`.
- **Objetivos (compact):**
  - progress per active goal: name, %, bar;
  - when this month has pending suggestions, one line "N contribuições para confirmar em <mês>", linking to `/goals`;
  - no Confirmar / Ajustar / Pular controls here.

### 5. Removed and kept

- **Removed:** the "Entradas × saídas" bar chart (`IncomeExpenseChart`). It repeated the two numbers printed above it, and the summary now carries them. `incomeExpenseOption` and its tests go with it.
- **Kept:** "Últimas transações" stays as it is, at the bottom, full width.

### 6. Goal suggestions on the Goals page

- The Goals page gets the suggestions section that today lives in `GoalsCard`: header with month and salary, and Confirmar / Ajustar / Pular per goal. Behavior, focus handling and error messages are unchanged; it is the same component logic, moved.
- The data comes from `GET /goals?month=<current>` (`useGoals`). The Goals page already loads it, and it already carries `suggestions`. There is no backend change and no dashboard call from the Goals page.
- The section sits above the goals list. It appears when there are suggestions, and also right after the last one was decided on this visit. In that case it reads "Tudo decidido em <mês>." and takes focus, as the dashboard card title did.

### 7. Navigation

- **Sidebar order:**
  - Dashboard, Transações, Orçamentos, Objetivos;
  - a muted group heading "Cadastros", then Contas bancárias, Categorias, Recorrentes;
  - Configurações pinned to the bottom of the sidebar;
  - Admin, for admins only, sits next to Configurações.
- **Bottom nav (mobile):** unchanged.
- **"Mais" menu:** the same order and grouping as the sidebar. The menu items are Objetivos, then the Cadastros group, then Admin for admins.
- No route changes.

## Non-goals

- No change to colors, fonts, radii or other tokens.
- No backend or API change.
- No month-over-month comparison. The dashboard API returns only the selected month; this needs backend work and is left for later.
- No change to other pages beyond adding the suggestions section to Goals. The rest is round B.

## Anti-goals

- Any new number on the dashboard that still lacks its context.
- Losing the monthly goal confirmation: the dashboard reminder line exists so it is not forgotten.
- Breaking accessibility that exists today: labelled lists, `role="img"` charts with labels, focus management in the suggestions flow, contrast.

## Layout

Desktop (lg):
```
[Month selector]
[ Entrou        | Saiu          | Sobrou / 23% do que entrou ]   full width
[ Para onde foi o dinheiro (≈ 3/5) | Orçamentos (≈ 2/5)     ]
[ Saldo        | Próximos 30 dias | Objetivos                ]
[ Últimas transações                                         ]   full width
```
On tablet (md), the third row wraps to two plus one. On mobile, everything is one column in the same order.

## Constraints

- All copy goes through i18n, in both locales (pt-BR and en).
- Money goes through `MoneyText` and `formatMoney` with the de-DE locale and EUR.
- Pure functions (shares, "Outras N", days left, summary) live in `features/dashboard/lib` with unit tests.
- Existing specs for the dashboard, goals and nav are updated, not deleted, where behavior moved.

## Calls made during review (not discussed with the user)

- **Budgets card:** lists every budget of the month, as today. There is no cap.
- **"Outras N" color:** the neutral muted chart color from `useChartColors`, never a category color.
- **Goal reminder:** shown only when the selected month is the current month, matching the Goals page, which works on the current month. On a past month the compact card shows progress only.
- **Days left on the last day:** "último dia do mês" replaces "faltam 1 dia".
- **"Outras N" threshold:** with up to 6 categories, all of them are listed. With 7 or more, the first 5 are listed and the rest become "Outras N" (N ≥ 2), so "Outras 1" never appears.
- **Salary category hint:** the compact dashboard card keeps today's "Escolha a categoria do salário…" link to `/goals`. The "no salary in <month>" message moves to the Goals page with the suggestions.
- **Confirming on the Goals page:** invalidates the same queries the dashboard confirm invalidates today (dashboard and goals), so the reminder line disappears afterwards.

## Left to the builder

- Exact i18n keys and whether "Outras N" uses plural forms.
- Whether the moved suggestions UI becomes a shared component or moves wholesale into the goals feature.
- The exact grid proportions within the described layout.
