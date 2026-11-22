# Month review: category weight and budget follow-up

Status: draft, waiting for the user's approval (2026-11-22). Item 3 of the improvement roadmap ("month close"). It extends the Revisão page (`/review`, PR #25).

## Intent

The user defined the month close as a step for understanding the month, not for locking it: "uma visão macro de como foram os gastos do mês, para entender quais categorias geram mais gastos e como otimizar os gastos nos meses seguintes."

The Revisão page already answers "what changed against our usual". Two things are missing:
- how much each category **weighs** in the month's spending;
- a way to turn that reading into an **action for the next months**.

## Goals

- **Category weight:**
  - Each spending category shows its share of the month's total expense ("% da saída").
  - The table can be ordered by size ("Maior gasto"), and that is the default.
- **Budget follow-up:**
  - Each category row shows the category's **current** budget and, against it, the reviewed month's status.
  - From the row, the user can set a budget (none yet) or adjust it (one exists), in a dialog with a suggested amount.
  - The dashboard already tracks budgets during the month, so next month's follow-up comes for free.

## Non-goals

- Budget history or a limit per month. The user accepted "orçamento atual": a past month is judged against today's limit.
- Locking the month, a "closed" status, or notes or decisions stored for a month.
- Backend changes or a migration.
- Changing the Orçamentos page or the dashboard.

## Anti-goals

- A second definition of budget status that disagrees with the Orçamentos page.
- A suggestion that looks authoritative where there's no basis for it.

## Approaches

The user picked **1**.

1. **Frontend only.** The page calls the existing `GET /api/v1/budgets?month=<reviewed month>` next to `/month-review` and joins the two by `categoryId`. Shares are computed in the browser. The existing `BudgetForm` opens in a dialog.
   - It needs no backend work, and budget rules stay in one place.
2. **Add budget fields to `/month-review`.** Rejected: budget data would then be served by two endpoints, and saving a budget would also have to invalidate the review. One request fewer isn't worth that.
3. **Budget history ("vale a partir de").** Rejected: it needs a V10 migration and changes the meaning of Orçamentos and the dashboard. The user said the current budget is fine.

## Decided behavior

### Weight and order

- **Share:** the category's amount divided by the month's `expense.cents`, shown as a whole percentage.
  - A share above 0 but under 1% shows "<1%".
  - A category at €0 this month (it dropped to zero) shows "—".
  - When the month's expense is 0 there are no rows anyway (the existing "noSpending" state).
- **Order toggle:** a two-option control above the table, "Maior gasto" | "Maior mudança". This is my call.
  - **Maior gasto (default):** by the month's amount (descending), then by name.
  - **Maior mudança:** the existing `reviewRows` order (absolute difference, then amount, then name).
  - With no comparison (the first month), the toggle is hidden and the order is by size.
  - The choice is local page state and is not kept in the URL. This is my call; a shared link opens on the default.

### Budget column

- **Source:** `GET /budgets?month=<reviewed month>`. Each budget's `spentCents` / `status` for that month comes from there, so the status matches the Orçamentos page's (NORMAL / WARNING ≥80% / EXCEEDED ≥100%).
- **Header:** "orçamento atual", with a short hint that it is today's limit applied to this month. This is my call on the wording.
- **Cell:**
  - With a budget: the limit and a status marker in the Orçamentos page's style. "Estourou" in the expense colour; "perto do limite" for WARNING; a ✓ for NORMAL. Each has a text alternative, so colour is never the only signal.
  - Without a budget: "—".
- **Loading or error of budgets:** the review table still renders, and the budget column shows a small skeleton or an inline error. A failed budget request doesn't block the review.

### Set / adjust

- **Action per row:**
  - "Definir" when the category has no budget.
  - "Ajustar" when it has one.
  - None for archived categories, because the API rejects budgets on them (`CATEGORY_ARCHIVED`).
- **Dialog:** the existing `BudgetForm`, with the category fixed to the row's category, in both modes.
- **Suggested amount** (prefilled, editable):
  - the 3-month average (`averageCents`) when the month has a comparison and the average is above 0;
  - otherwise the month's amount;
  - rounded **up** to a whole euro.
  - The suggestion is labelled as "média dos últimos N meses" or "gasto deste mês", so it is never presented as advice. This is my call.
  - When adjusting, the field starts on the current limit, and the suggestion is shown beside it with a one-click "usar sugestão".
- **After saving:** the budget queries are invalidated as the Orçamentos page already does, so the column, the Orçamentos page and the dashboard update.

### Layout

- **Desktop columns:** category · month · % · average · difference · current budget · action.
- **Mobile:** average and % are hidden, as average already is today. The budget status and the action stay, with the action as an icon button that has an aria-label. The builder may stack the budget under the category name if the row is too tight.

```
Para onde foi        [Maior gasto | Maior mudança]
                  outubro  % saída  média 3m  dif.    orçamento atual
Casa                €900    37%      €900      —      €950  ✓          [Ajustar]
Mercado             €520    21%      €430    +€90 ▲   €450  estourou   [Ajustar]
Restaurantes        €210     9%      €120    +€90 ▲   —                [Definir]
```

## Build outline

- **`features/review/lib/review.ts`:**
  - share formatting;
  - a size order next to the existing change order;
  - the suggested amount (round up to a euro).
  - Unit tests for each.
- **`ReviewCategories.vue`:**
  - takes the budgets (keyed by category id) and the order;
  - renders the new columns and the action.
- **`ReviewPage.vue`:**
  - runs `useBudgets(month)` once the month is known;
  - holds the order toggle and the dialog state.
- **`BudgetForm.vue`:** optional props for a fixed category and an initial or suggested limit. Its existing uses are unchanged.
- **i18n:** new keys in `pt-BR.json` and `en.json`.
- **Tests:** a `ReviewPage.spec` case for the share, the toggle, the budget column states and opening the dialog prefilled.

## Left to the builder

- The exact component split, for example a `ReviewBudgetCell`.
- The dialog component; reuse what the Orçamentos page uses.
- The final wording of the new strings, in the existing tone.
