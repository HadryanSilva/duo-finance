# Savings goals — design

**Date:** 2026-11-16 · **Size:** project (backend module + migration + frontend page + dashboard changes)

## Intent and why

The couple wants to save toward named goals, such as "Videogame novo" or "Reserva de emergência", by setting aside a percentage of their salary each month. In Portugal they have no way yet to keep reserves apart from day-to-day money. They cannot open a Revolut savings account for documentation reasons, and they may use Wise or another account later. Where the money physically sits will change over time. The app should therefore manage the goals as an **earmark inside the app**, without knowing or caring where the money is.

The underlying wish is about usability, practicality and **information quality**. The app should show how much has been set aside for each goal, how far each goal is from its target, and how much money is actually free to spend.

## Decisions taken with the user

- **Earmark only.** A goal creates no transfers and no transactions, and needs no savings account. Its money is a number the app keeps.
- **Contributions are suggested and confirmed (option B).** Each month the app suggests `percentage × salary` per goal. A member confirms it, adjusts it (an amount for that month only) or skips the month. Nothing is set aside automatically. Rejected alternatives:
  - **A, automatic:** shows money set aside that may not exist.
  - **C, manual only:** loses the percentage-of-salary idea.
- **The salary is the couple's combined salary (option A).** It is the sum of salary income in the month, and each goal has one percentage. Rejected alternative: a per-partner percentage. It may be added later.
- **Built as a new backend module** shaped like `budgets`. Rejected alternatives:
  - **Goals as bank accounts with transfers:** would create fake transactions and pollute lists, reports and balances.
  - **Recurring rules:** they post automatically, which is option A.
  - **Browser-only storage:** the partner could not see the goals.

## Goals

1. Create, edit, conclude and delete goals. A goal has a name, a target amount, an optional target date and a monthly percentage.
2. Pick which income category counts as "salary" for the account.
3. On the dashboard, for a month that has salary income, offer per-goal suggestions to **Confirmar / Ajustar / Pular**.
4. Record contributions, extra deposits and withdrawals per goal; show progress (saved / target, %).
5. Show **saldo livre** = total balance − total set aside in active goals, next to the existing total balance.
6. Both members see and act on all goals, as with every other entity. Each movement records its author.

## Non-goals

- No real money movement, transfers or transactions created by goals.
- No link between a withdrawal and the expense transaction that used the money.
- No per-partner percentages or split contributions.
- No interest or yield tracking (for example Wise returns).
- No notifications or emails about pending suggestions.

## Anti-goals

- A goal must never show money as set aside unless a member confirmed it, adjusted it or deposited it.
- Goals must not change any existing number: transaction lists, income/expense charts, category spending and budgets stay exactly as today. Saldo livre is an **additional** figure, not a replacement for the total balance.
- A goal must not require a savings account or any specific bank.

## Behaviour

### Salary category

- The account has one optional **salary category**, an INCOME category, set on the Objetivos page: "O salário vem da categoria: [select]".
- **Salary for month M** = the sum of INCOME transactions in that category dated in M (business date), by either member.
- If the category is unset, archived or deleted, the app makes no suggestions. The Objetivos page and the dashboard card ask the user to choose one. Deleting the category sets the reference to null.

### Goals

- **Fields:**
  - name: 1–100 chars, unique among active goals, case-insensitive;
  - target amount in cents: > 0;
  - optional target date;
  - monthly percentage: a whole number from 0 to 100. 0 means "no monthly suggestion, deposits only".
- **Percentage cap:** the percentages of all ACTIVE goals, REACHED ones included, may sum to at most 100. Above that, the request is rejected with a field error. If any reached goals exist, the error suggests concluding them. *(Builder-check call: counting them keeps the cap simple, and a withdrawal can turn a reached goal back into an active one.)*
- **Status:**
  - **ACTIVE:** the normal state.
  - **REACHED:** derived when saved ≥ target. It stays active but gets no more suggestions, and the UI invites the user to conclude it.
  - **COMPLETED:** concluded. It moves to the history, its saved amount is released (no longer counted in the total set aside) and it stays visible read-only. While a goal is COMPLETED, its movements cannot be added or undone.
- **Concluir is allowed at any time, even below the target** ("desisti / usei o dinheiro"). The confirmation dialog states how much will be released.
- **Reabrir:** a COMPLETED goal can be reopened. It becomes ACTIVE again and its saved amount, recomputed from its movements, counts again. If reopening would push the percentages past 100, it is rejected with the same cap error. *(Builder-check calls: a mistaken Concluir needs a way back, and reopening costs no data because the movements are kept.)*
- **Deleting a goal:** removes it and its movements. Confirmation is required. The money is released.
- **Editing:** name, target, date and percentage can change at any time. Edits never change recorded movements. Suggestions that are still pending, for the current month or a missed earlier one, are computed with the goal's **current** percentage and target. *(Builder-check call: this keeps a single percentage column and no history table.)*

### Movements

Each movement has: goal, type, amount in cents (≥ 0), month (YYYY-MM, for CONTRIBUTION and SKIP), an optional note (≤ 200 chars), author and creation time.

| Type | Created by | Effect on saved |
|---|---|---|
| CONTRIBUTION | Confirmar (suggested amount) or Ajustar (amount typed) for month M | + amount |
| SKIP | Pular for month M | none (amount 0) |
| DEPOSIT | "Adicionar valor" at any time | + amount |
| WITHDRAWAL | "Resgatar" at any time; amount ≤ saved | − amount |

- A goal has at most **one CONTRIBUTION or SKIP per month**, unique on (goal, month).
- **Ajustar:** the amount must be > 0 (Pular is the way to record zero) and ≤ target − saved. It may be above the suggested amount. A month's CONTRIBUTION exists only through the suggestion card, so a month with no salary has none. Money set aside in such a month goes in through **Adicionar valor**. *(Builder-check calls.)*
- **DEPOSIT:** amount > 0 and ≤ target − saved. **WITHDRAWAL:** amount > 0 and ≤ saved.
- Any movement can be **undone**, which deletes it. Undoing the month's CONTRIBUTION or SKIP brings that month's suggestion back. Undoing a movement that would make saved negative is rejected.
- **saved(goal)** = Σ CONTRIBUTION + Σ DEPOSIT − Σ WITHDRAWAL, always ≥ 0.

### Suggestions

For a month M and each ACTIVE goal that meets all of the following:
- its percentage is > 0;
- it was created in or before M;
- M is not in the future;
- it has no CONTRIBUTION or SKIP for M;
- it is not REACHED;

and when salary(M) > 0:
- the suggested amount = round(salary(M) × percentage / 100), capped at target − saved.

The dashboard shows the suggestions for its **selected month**, so a month missed earlier can still be confirmed. The suggestions are computed on read, not stored. If the salary grows later in the month, for example when the second salary arrives, unconfirmed suggestions grow with it. Confirmed contributions never change.

### Information shown

- **Dashboard:**
  - a "Objetivos" card with each active goal's progress bar (saved / target, %) and that month's pending suggestions with Confirmar / Ajustar / Pular.
  - the BalanceCard keeps the total balance and adds **Saldo livre** = total balance − Σ saved of active goals (REACHED ones included), with a one-line explanation. It may be negative and is shown as such.
  - **Saldo livre appears only when the dashboard shows the current month**, where the balance is today's balance. For past months, the balance is that month's closing balance and the goals' state back then is not recorded, so the figure is omitted rather than mixing two points in time. *(Builder-check call: this needs no dated movements and no completion history in V8.)*
- **Objetivos page:**
  - the active goals, the salary category picker and the completed goals (history);
  - per goal: saved / target, %, remaining, monthly percentage, the movement history (type, month or date, amount, author, note, undo) and the actions Adicionar valor, Resgatar, Editar, Concluir, Excluir.
  - **Projection**, when a target date exists: "Para chegar até <data> faltam € X/mês". When there is no date and at least one contribution: "No ritmo atual (média das últimas 3 contribuições) chega em <mês/ano>".

## Constraints

- Follow the existing stack and patterns:
  - backend: Spring Boot 4 / Java 25, a module shaped like `budget/` (controller/dto/model/repository/service), account scoping, the entitlement interceptor and the ProblemDetail error codes;
  - frontend: Vue 3, TanStack Query, vee-validate + zod, the shared UI (PageHeader, SidePanel, ConfirmDialog, MoneyInput, MoneyText, BudgetProgressBar-style bar) and the design guard.
- Use one new Flyway migration, **V8**. V1–V7 are frozen.
- Money in EUR cents. pt-BR and en strings in both locales.
- New error codes go in the backend `ErrorCode` enum and the frontend `error-codes.ts`. The i18n spec checks that the two match.
- Navigation: add "Objetivos" to the sidebar and to the user menu's "Mais" list (mobile), since the bottom nav has no room.
- Mobile-first behaviour from the 2026-11-15 responsiveness work: SidePanel forms; on phones, row actions go in the "⋯" ActionsMenu.

## Left to the builder

- The exact table and column names, indexes and locking. A goal write should lock the account consistently with existing rule writes (see the known lock-order note: lock the account before the entity).
- Whether the dashboard endpoint embeds goals and suggestions in `DashboardResponse`, or the card calls a separate `/goals/summary?month=` endpoint. Prefer whatever keeps the dashboard one request, like budgets.
- The precise copy, and the empty states for: no goals, no salary category, no salary this month.
