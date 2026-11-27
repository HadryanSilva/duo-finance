# Revolut pockets live in the same account: design

Date: 2026-11-27. Follows `2026-11-26-revolut-pockets-goals-design.md` (PR #36, live since release #37).

## Intent

A Revolut pocket ("Poupanças" in the statement) is not another bank account. It is money in the same Revolut
account, set apart inside the Revolut app. In Duo Finance the user keeps one "Revolut" bank account holding the total
balance (Current plus pockets). A pocket move must not change that balance; it only sets money apart for a goal, which
is what a goal already is in Duo Finance (earmark-only: free balance = total balance − saved).

PR #36 modelled pockets as a second bank account: every pocket move became a TRANSFER from Current to a "pockets
account" the user had to choose on upload, and choosing the statement's own account skipped the rows
(`POCKET_SAME_ACCOUNT`). That is the problem the user hit.

The user has not imported any statement in production yet, so no data needs converting.

## Goals

- A pocket move in the statement becomes **only** a goal movement: a CONTRIBUTION when money goes into the pocket,
  a WITHDRAWAL when it comes out. No transaction is created and the Revolut balance does not move.
- The free balance then matches the "Atual" balance Revolut shows.
- Re-importing an overlapping statement does not duplicate goal movements: those rows show as already imported.
- Undoing an import removes the goal movements it created, with the same safety rules as today.
- Every other row of the savings product (interest, for example) lands in the statement's own account, as income or
  expense like any other row.
- The upload no longer asks for a pockets account.

## Non-goals

- No change to how pocket rows are recognised (pt-PT and English wordings, leg pairing, synthesised missing leg).
- No change to the review's goal picker, goal creation dialog, running withdrawal warnings or month targets.
- No conversion of existing data (there is none in production).

## Anti-goals

- Pocket moves must not appear in the transaction list, in reports or in totals.
- A pocket move must never change any bank account balance.

## Approaches considered

- **A. A pocket move is only a goal movement (chosen).** The goal movement carries the row's import fingerprint and
  its import batch, so deduplication and batch undo work without a transaction. It matches the user's model: the
  record lives in the goal's history, and the transaction list holds only money that entered or left the account.
  Cost: two new columns and a new path for deduplication and batch undo.
- **B. A transfer from the Revolut account to itself.** It would keep most of PR #36 as is, but it puts
  "Revolut → Revolut" rows in the transaction list that reports and forms must ignore, which is the "another account"
  feeling the user objected to.

The user picked A.

## Design

### Data (V12)

- `goal_movements.import_fingerprint text NULL` and `goal_movements.import_batch_id uuid NULL REFERENCES
  import_batches(id) ON DELETE CASCADE` (my call: if a batch row ever goes, its movements go with it, as an undo
  would; account deletion stays clean). A unique index on `(account_id, import_fingerprint)` where the fingerprint is not null.
- `goal_movements.transaction_id` and its hooks go away: drop the column (its unique index and the ON DELETE CASCADE
  go with it). In production it is always null, because no pocket import has happened.
- `accounts.pockets_bank_account_id` goes away: drop the column.

### Plan (preview)

- The savings product (`Poupanças` / `Savings`) always belongs to the statement's own bank account. It is no longer
  offered in the product mapping, and it never needs a mapping.
- A pocket move (one per move: a pair of legs, or a lone leg) stays one planned row with `pocket` set. Its `rowId`
  stays the fingerprint the rules of PR #36 give it (the negative leg's, or the synthesised missing leg's), so the
  fingerprints it stores now match the ones later statements compute.
- In the preview such a row keeps `kind: TRANSFER` for shape, with `bankAccountId` = the statement's account and
  `destinationBankAccountId` = null. The review shows it as a pocket move, not as a route between accounts.
- A pocket row is `ALREADY_IMPORTED` when its fingerprint is in `goal_movements.import_fingerprint` for the account,
  read together with the transaction fingerprints.
- Skip reasons `POCKET_NO_ACCOUNT` and `POCKET_SAME_ACCOUNT` are removed. `PlanContext.pocketsBankAccountId` and the
  multipart field `pocketsBankAccountId` go away.
- Savings rows that are not pocket moves (interest) are planned like any other row of the statement's account.
- Pocket rows are never `POSSIBLE_DUPLICATE` (my call): that check compares with manual transactions, and a pocket
  move has no transaction to match.

### Commit

- Pocket rows never create transactions. For an included pocket row with a goal, the commit records the goal movement
  in statement order (`APPLY_ORDER`, under the account lock), as today: a CONTRIBUTION for the full amount (above the
  target is accepted, and it replaces the month's skip as today), and a WITHDRAWAL capped at what the goal holds by
  then. The movement stores the fingerprint and the batch.
- A capped withdrawal of 0 records nothing (as today). An included pocket row without a goal records nothing; the
  review says so instead of "continua no saldo livre".
- The batch counts transactions and goal movements created; a commit that creates neither deletes its batch, as today.
- A pocket row whose goal is no longer active fails the commit as today.

### Undo and edits

- Batch undo: lock the account, refuse if any goal would end below zero without the batch's movements
  (`GOAL_AMOUNT_INVALID`), restore the skips they replaced, delete them, then delete the batch's transactions.
- A single imported movement can still not be undone from the goal history (`GOAL_MOVEMENT_IMPORTED`). The way out is
  undoing the import, so re-importing never meets a half-undone file. The hint becomes: "Veio da importação: desfaça a
  importação para remover" (and its English twin).
- Transaction edit and delete no longer touch goal movements: `followTransaction`, `beforeTransactionRemoved` and
  their calls in `TransactionService` go away.

### Frontend

- The upload step loses the pockets account selector and its state; the page no longer sends `pocketsBankAccountId`.
- The product mapping no longer lists the savings product.
- A pocket row in the review shows the date, then "Pocket <name>" (or "Pocket" when the wording has no name) instead
  of the transfer route, and the goal line as today.
- "Sem objetivo: este valor continua no saldo livre" becomes "Sem objetivo: nada será registrado" (English: "No goal:
  nothing will be recorded").
- Skipped-row copy for the two removed reasons goes away. `schema.ts` is regenerated from the backend.

## Left to the builder

- Where the goal-movement fingerprints are read (the import context loader is the natural place).
- Test layout, following the existing planner, commit, batch and controller tests.
- Removing code made dead by this change, together with its tests.
