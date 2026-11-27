# Revolut Pockets → goals — design

**Date:** 2026-11-26 · **Size:** project (two parts, built in order)
**Related:** savings goals spec `2026-11-16-savings-goals-design.md` (this changes part of it), import spec/code in
`backend/.../imports`.

## Intent

The user started using Revolut **Pockets** to set money aside for goals (emergency fund, "Setup" = parts for a new
computer). Each move into a pocket shows up in the statement, so importing the statement should:

1. keep bank-account balances right without manual work;
2. record the money moved into or out of a pocket as a contribution to (or withdrawal from) the matching goal.

The user said every pocket corresponds to a goal. They also said the old "one contribution per month" rule is wrong
for real life: after paying the bills there may be extra money to put away, so **every** contribution must be recorded,
however many there are in a month. The monthly suggestion becomes a **monthly target with progress**.

## What the statement looks like (sample, pt-PT export, 2026-11)

```
Tipo,Produto,Data de início,Data de Conclusão,Descrição,Montante,Comissão,Moeda,Estado,Saldo
Transferência,Poupanças,2026-11-26 14:34:39,2026-11-26 14:34:39,Carregamento de subconta EUR Reserva de Emergência de EUR,250.00,0.00,EUR,CONCLUÍDA,250.00
Transferência,Poupanças,2026-11-26 14:54:41,2026-11-26 14:54:41,Carregamento de subconta EUR Setup de EUR,100.00,0.00,EUR,CONCLUÍDA,350.00
Transferência,Poupanças,2026-11-26 14:55:30,2026-11-26 14:55:30,Carregamento de subconta EUR Reserva de Emergência de EUR,1000.00,0.00,EUR,CONCLUÍDA,1350.00
...
Transferência,Atual,2026-11-26 14:34:39,2026-11-26 14:34:39,Carregamento de subconta EUR Reserva de Emergência de EUR,-250.00,0.00,EUR,CONCLUÍDA,3010.40
Transferência,Atual,2026-11-26 14:54:41,2026-11-26 14:54:41,Carregamento de subconta EUR Setup de EUR,-100.00,0.00,EUR,CONCLUÍDA,2910.40
```

Facts this design relies on:

- Every pocket move has two legs with the same `Data de início`, opposite amounts and **identical descriptions**: one on
  product `Atual` (Current) and one on product `Poupanças`.
- **All pockets share one product** (`Poupanças`), and its `Saldo` is the total of all pockets.
- The pocket name is in the description: `Carregamento de subconta EUR <pocket> de EUR` (a deposit).
- **One leg can be missing.** The +1000 deposit at 14:55:30 has no −1000 on `Atual` in this export. The user confirmed
  the money came from the account balance, so the Current leg will only appear in a later export.
- The user has **never withdrawn** from a pocket, so the withdrawal format is unknown. The design must be safe without it.

How it works today: if `Poupanças` is mapped to its own bank account on the upload step ("Outros produtos do extrato"),
the existing pairing (`ImportPlanner.pairTransfers`) turns the two legs into a TRANSFER, which is already right for
balances. If it is ignored, the Current leg becomes an EXPENSE, which is wrong. An unpaired leg becomes INCOME or
EXPENSE, and when its partner arrives in a later import it does not pair (`pairable` refuses a stored non-TRANSFER leg),
so the account would get an inflated income and expense. Goals are an earmark inside the app (`goal_movements`), and the
free balance is the total balance minus the goals' saved amounts.

## Goals

**Part 1: contributions without a monthly limit** (applies to everyone, with or without pockets)

- A goal can receive any number of contributions in a month. Each contribution has a date, and its month is the month
  of that date.
- "Depósito avulso" (DEPOSIT) stops being a separate concept: existing DEPOSIT movements become contributions dated
  on their creation day. WITHDRAWAL stays as it is.
- The monthly suggestion (goal % × the couple's salary for the month) becomes a **monthly target with progress**: the
  sum of the month's contributions against the target, done once it is met. "Pular" stays and means "not saving for
  this goal this month".
- Projection pace = average of the **monthly totals** of the last three months that had contributions (today it uses
  the last three single contributions).
- Manual entry stays: a "Registrar" action records a contribution, pre-filled with what is left of the month's target
  (or empty when there is none or it is met).

```
Reserva de Emergência      Novembro: 1.250 € de 300 € ✓
Setup                      Novembro:   100 € de 180 €  [+ Registrar]
```

**Part 2: pockets in the import**

- A goal gets an optional **"Pocket da Revolut"** name. It is matched case- and accent-insensitively against the pocket
  name parsed from descriptions, and it is unique among active goals of the account.
- A pocket row (product `Poupanças`, whose description names a pocket) is **always a TRANSFER** between the account
  mapped to `Current` and the account mapped to `Poupanças`, never INCOME or EXPENSE, **even when its partner leg is
  missing**. When the partner shows up in a later import, it is recognised as already imported.
- In the import review, each pocket transfer shows its goal:
  - **already linked** → "→ contribuição para *Reserva de Emergência*" (or "retirada de" for money moving back);
  - **name matches an active goal without a pocket** → that goal is pre-selected, and confirming the import saves the
    link on the goal;
  - **no match** → a picker: existing active goals, or **"+ Criar objetivo '<pocket>'"**, which opens the goal form
    pre-filled with the name and the pocket (the user fills in the target and %);
  - **left without a goal** → it is imported as a plain transfer, with a warning that this money still counts in the
    free balance.
- Committing the import writes the transfer and its goal movement (CONTRIBUTION for money into a pocket, WITHDRAWAL for
  money out) **linked to each other**. Deleting the transaction, or undoing the import batch, removes the movement.
- **Withdrawals (safe behaviour, format unknown):** a `Poupanças` row with a negative amount that pairs with a positive
  Current row (or stands alone) is a transfer Pockets → Current. If a known pocket name appears in its description, its
  goal is pre-selected; otherwise the review asks for the goal, like an unmatched deposit.
- Contributions coming from the import count towards the monthly target exactly like manual ones.

## Non-goals

- No Revolut API or Open Banking: the CSV import stays the only source.
- No per-pocket bank accounts: all pockets are one bank account (the product is one, and so is its balance). What is in
  each pocket is visible through its goal.
- No automatic import or contribution without the review step (the user confirms everything, as today).
- Other banks' savings features, and pocket interest or fees (if a `Poupanças` row does not name a pocket, it is
  handled as today: INCOME or EXPENSE on the pockets account).

## Anti-goals (would make this a failure even if it "works")

- A pocket move inflating income or expenses (counted as INCOME/EXPENSE anywhere, including the unpaired-leg case).
- A goal's saved amount drifting from the transfers it came from (a deleted transfer leaving its contribution behind,
  or a re-import creating a second contribution).
- Losing or silently changing existing goal history in the migration.
- A contribution recorded twice when the same statement, or an overlapping one, is imported again.

## Approaches considered

- **A. Inside the import (chosen by the user).** The planner recognises pocket rows, makes them transfers even when
  unpaired, and attaches a goal. The review shows and edits it, and the commit writes the transfer plus a linked goal
  movement. Everything happens in one review, at the moment the user is already checking the statement. Cost: the
  review gets richer and the import module learns about goals.
- **B. Inbox on the Goals page.** The import only creates transfers (with the unpaired-leg fix), and the Goals page
  lists "pocket deposits to classify". It also covers manual transfers, but it is an extra step after each import that
  is easy to forget, and the free balance is wrong until it is done.
- **Dropped: derive saved amounts from transfers** (no goal movements for pockets). It is too implicit: deleting a
  transfer would change a goal silently, and manual entry would stop fitting.

## Decided with the user

- A deposit into a pocket **is** a contribution. The user wants every contribution recorded, many per month.
- The monthly suggestion becomes a monthly target with progress, and "Pular" stays.
- Every pocket is a goal. Unlinked pockets can be linked to an existing goal or create a goal from the review.
- Withdrawals use the safe behaviour (name recognised when present, otherwise asked in the review).
- Approach A.
- Unpaired pocket legs are always transfers, and the late partner is recognised (proposed by Claude, accepted).
- **Limits** (proposed by Claude, accepted):
  - An imported deposit larger than what is left to the target is accepted. The goal goes above its target and shows
    as reached.
  - An imported withdrawal larger than the saved amount is recorded only up to the saved amount (the goal goes to 0),
    and the review warns about the difference.
  - Money already in a pocket before it was linked is entered by the user with "Registrar".
- **No statement with `Poupanças` rows has been imported yet** (user, 2026-11-26), so no repair of stored rows is
  needed.

## Constraints

- Migrations: V9 is the latest. This adds **V10** (and V11 if Part 2 ships separately); V8 and V9 are frozen.
- All money is integer cents, EUR, as everywhere else.
- Import rules stay deterministic and pure (`ImportPlanner` takes rows plus a context and returns a plan). Values
  always come from the file at commit (the commit re-plans), and user decisions only choose (include, category, goal).
- Fingerprint deduplication must keep working for every existing batch. Already-imported rows must not change status.
- The free-balance formula (total balance − saved) stays.
- Server runs on UTC; "current month" checks reuse `shared/time/RunningMonths` (see goals memory).
- i18n pt-BR and en for every new string, with key parity enforced by `i18n.spec.ts`.
- Frontend follows the design guard (tokens, `PageHeader`, `NativeSelect`/`Checkbox`, no raw controls).

## Claude's calls (not explicitly decided by the user)

1. **Data model, Part 1.** Drop the unique index `uq_goal_movements_month` for CONTRIBUTION. SKIP stays one per goal
   and month (a partial unique index on SKIP only). Add `occurred_on date` to `goal_movements` (backfilled from
   `created_at`, and from `month` for old contributions), so `month` stays derivable. DEPOSIT rows are migrated to
   CONTRIBUTION with `month` = month of `occurred_on`. The type check keeps DEPOSIT out from now on.
2. **Skip vs contributions.** Contributing in a skipped month removes that month's skip. Skipping a month that already
   has contributions is refused (`GOAL_MONTH_HAS_CONTRIBUTIONS`).
3. **Target met.** The month counts as "decided" for the projection (the existing `decidedThisMonth`) when it is
   skipped or its contributions reach the target. A goal without a target (no salary, or 0%) is decided once it has any
   contribution that month.
4. **Link.** `goal_movements.transaction_id uuid NULL REFERENCES transactions(id) ON DELETE CASCADE`, unique when not
   null. Deleting the transaction (directly or through a batch undo) deletes the movement. A movement with a
   transaction cannot be undone from the Goals page: the UI says it came from the import and points to the transaction.
   Editing the linked transfer's amount or date updates the movement's amount and date in the same transaction.
5. **Goal ↔ pocket.** `goals.revolut_pocket varchar(100) NULL`, unique (normalised: lower-cased and accent-stripped)
   among active goals of an account. Completing a goal keeps the name but frees it for a new goal. Editable in the goal
   form.
6. **Recognising pocket rows.** The savings product is recognised by a set of names (`Poupanças`, `Savings`). The
   product value itself is **not** aliased, so stored fingerprints of any row stay exactly as they are today.
   Pocket-name patterns are kept in one place, per language. The known one is pt-PT `^Carregamento de subconta [A-Z]{3}
   (.+) de [A-Z]{3}$`. An unknown wording still makes the row a pocket transfer when its product is `Savings` and its
   Type is a transfer (`Transferência`/`Transfer`); the goal is then asked in the review.
7. **Missing partner leg (either side).** A paired transfer stores the fingerprint of its **negative** leg. For an
   unpaired pocket leg, the planner builds the transfer and gives it the fingerprint the negative leg has or **would
   have**:
   - **deposit** with only the Savings leg: synthesise the Current leg (product `Current`, negated amount string, same
     started date, description and currency);
   - **withdrawal** with only the Current leg: synthesise the Savings leg, using the savings product name in the same
     language as the file's own Current product (`Atual` → `Poupanças`, `Current` → `Savings`).

   When the real leg arrives later, the pair matches the stored TRANSFER and shows as ALREADY_IMPORTED.
8. **Pocket rows on Current too.** A `Current` row whose description names a pocket (or whose Type is a transfer
   and whose description matches a known pocket wording) is a pocket transfer even when the file has no Savings rows.
   The other side is the **pockets bank account**, which is remembered per account: a new nullable
   `accounts.pockets_bank_account_id`, set the first time the Savings product is mapped in an import and editable
   there. Without it, the upload step asks for it when pocket rows are present. A row whose other side is still unknown
   is skipped with a new reason `POCKET_NO_ACCOUNT`. The same applies to a Savings row without a Current mapping.
9. **Review decision.** `RowDecision` gains `goalId` (nullable) and `createGoal` is **not** sent to the server: the
   "Criar objetivo" path creates the goal through the normal goals API first, then selects it. The commit validates
   that the goal is active and belongs to the account, and saves the pocket link on it when it had none.
10. **Re-import.** A row that is ALREADY_IMPORTED never creates a movement. Commit is idempotent per transaction through
   the unique `transaction_id`.
11. **Dates and salary.** A contribution counts on the transfer date (manual: the chosen date, default today), in any
    month up to the current one. It needs no salary; the salary only feeds the monthly target. The goal's "first
    month" for the projection and for skips is the earliest of its creation month and its first movement, so a goal
    created from the review accepts the statement's earlier deposits.
12. **Dashboard.** `GoalsCard` and the suggestions show the monthly target progress instead of the "confirmar"
    prompt. `BalanceCard` is unchanged.

## Left to the builder

- Exact API shapes (`GoalResponse` gains this month's contributed and target amounts and `revolutPocket`; preview rows
  gain the pocket name, goal id and goal status), and the endpoint naming for "Registrar".
- Component breakdown of the review row and the goal picker, and copy (pt-BR + en).
- How the goal form opens from the import review (dialog over the review, keeping review state).
- The migration SQL details and the test fixtures (reuse the sample above, anonymised).
