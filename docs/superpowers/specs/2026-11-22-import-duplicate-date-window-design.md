# Import: possible-duplicate check spans the purchase-to-completion window

## Why

On 2026-11-15 the user typed four Revolut card payments by hand (Burger King, Lefties, Vilanova, Proton) while they
were still pending, dated 15/11, the day of purchase. On 2026-11-22 a full November import brought the same payments
dated 16/11, their Revolut completion date. The preview marks a row as `POSSIBLE_DUPLICATE` only when a manual
transaction has the **same date**, kind, amount and bank account (`ImportPlanner.build`). 15/11 is not 16/11, so the
rows came in as `NEW` and the payments were duplicated. Confirmed in production: the four 15/11 rows have no
`import_fingerprint`, no batch, and were created by the user at 22:51–22:52 UTC on 15/11.

## Goal

A manual transaction dated anywhere from the row's purchase day (Started Date) to its completion day (Completed
Date), inclusive, counts as a possible duplicate, provided kind, amount and bank account match.

## Non-goals

- The import still stores the completion date. Switching to the purchase date was considered and set aside (option 2
  below).
- No change to fingerprints, to `ALREADY_IMPORTED`, or to rows already stored.
- No fuzzy description matching. Description stays out of the check, as today.
- The four duplicated rows in production are cleaned up by the user in the app, not by a migration.

## Approaches

1. **Widen the date match to [started day, completed day]** (chosen). Small, local, touches only the planner and the
   context loader, and leaves stored data alone. The user still decides in the preview.
2. **Store the purchase date instead of the completion date.** The dates would match what the Revolut app shows and the
   existing same-day check would work. Rejected for now: already imported transactions would keep the old dates, and
   payments near a month boundary would move to a different month, which affects budgets, month review and goals.

## Design

- `ImportPlanner.build`: a row is `POSSIBLE_DUPLICATE` when, for any date `d` from `startedAt.toLocalDate()` to
  `dateOf(row)` inclusive, `manualTransactions` contains `DuplicateKey(d, kind, amount, account)`. A row without a
  completion date (or one completed on its purchase day) gives a single-day window, the same as today.
  `ALREADY_IMPORTED` still wins.
- This applies to every planned row built from that Revolut row: the row itself, its fee, and transfer legs. Fees and
  transfers get the window for free because they go through `build`.
- `ImportContextLoader.manualTransactions`: the query's lower bound becomes the earliest **started** day across the
  rows, so manual transactions dated before the earliest completion date are loaded.
- Tests: a planner test for the case above (manual on the purchase day, row completed the next day → possible
  duplicate), one for a manual transaction outside the window (still `NEW`), and a loader/integration test showing the
  wider query bound.

## Left to the builder

- Whether to loop over the days or store manual transactions in a per-key date list. Windows are usually 0–2 days.
