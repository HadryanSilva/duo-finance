import type { FieldError } from '@/shared/api/problem'
import type { PlannedRow } from '@/shared/api/types'
import type { RowDecision } from '../api/imports-api'

export type ReviewRow = PlannedRow & {
  include: boolean
  categoryId: string | null
  /** Pocket rows: the goal the move contributes to or withdraws from (null: none, nothing is recorded). */
  goalId: string | null
}

const locked = (r: ReviewRow) => r.status === 'ALREADY_IMPORTED'

export function initReview(rows: PlannedRow[]): ReviewRow[] {
  return rows.map((r) => ({
    ...r,
    include: r.status === 'NEW',
    categoryId: r.suggestedCategoryId,
    goalId: r.pocket?.goalId ?? null,
  }))
}

/** Other rows sharing the backend-computed descriptionKey and kind. */
export function sameGroup(rows: ReviewRow[], rowId: string): string[] {
  const target = rows.find((r) => r.rowId === rowId)
  if (!target) return []
  return rows
    .filter(
      (r) =>
        r.rowId !== rowId && r.descriptionKey === target.descriptionKey && r.kind === target.kind,
    )
    .map((r) => r.rowId)
}

export function setCategory(
  rows: ReviewRow[],
  rowId: string,
  categoryId: string | null,
  applyToGroup: boolean,
): ReviewRow[] {
  const target = rows.find((r) => r.rowId === rowId)
  if (!target || target.kind === 'TRANSFER' || locked(target)) return rows
  const ids = new Set([rowId, ...(applyToGroup ? sameGroup(rows, rowId) : [])])
  return rows.map((r) => (ids.has(r.rowId) && !locked(r) ? { ...r, categoryId } : r))
}

/** Mirrors the backend's PocketNames.normalise: lower-cased, accents stripped, spacing collapsed. */
const normalisePocket = (name: string) =>
  name.normalize('NFD').replace(/\p{M}/gu, '').toLowerCase().trim().replace(/\s+/g, ' ')

/** Sets a pocket row's goal, and the other unlocked rows of the same (named) pocket. */
export function setGoal(rows: ReviewRow[], rowId: string, goalId: string | null): ReviewRow[] {
  const target = rows.find((r) => r.rowId === rowId)
  if (!target?.pocket || locked(target)) return rows
  const name = target.pocket.name === null ? null : normalisePocket(target.pocket.name)
  const samePocket = (r: ReviewRow) =>
    r.rowId === rowId ||
    (name !== null && r.pocket?.name != null && normalisePocket(r.pocket.name) === name)
  return rows.map((r) => (samePocket(r) && !locked(r) ? { ...r, goalId } : r))
}

export function setInclude(rows: ReviewRow[], rowId: string, include: boolean): ReviewRow[] {
  const target = rows.find((r) => r.rowId === rowId)
  if (!target || locked(target)) return rows
  return rows.map((r) => (r.rowId === rowId ? { ...r, include } : r))
}

export function totals(rows: ReviewRow[]): {
  count: number
  incomeCents: number
  expenseCents: number
} {
  let count = 0
  let incomeCents = 0
  let expenseCents = 0
  for (const r of rows) {
    if (!r.include) continue
    count++
    if (r.kind === 'INCOME') incomeCents += r.amountCents
    else if (r.kind === 'EXPENSE') expenseCents += r.amountCents
  }
  return { count, incomeCents, expenseCents }
}

export function canSubmit(rows: ReviewRow[]): boolean {
  const included = rows.filter((r) => r.include)
  return (
    included.length > 0 && included.every((r) => r.kind === 'TRANSFER' || r.categoryId !== null)
  )
}

/** Decisions for the included rows only: the backend treats a row without a decision as not imported. */
export function toDecisions(rows: ReviewRow[]): RowDecision[] {
  return rows
    .filter((r) => r.include && !locked(r))
    .map((r) => ({
      rowId: r.rowId,
      include: true,
      categoryId: r.kind === 'TRANSFER' ? null : r.categoryId,
      // the backend refuses a goal on any other row, so the field is left out there
      ...(r.pocket ? { goalId: r.goalId } : {}),
    }))
}

const DECISION_FIELD = /^decisions\[(.+)\]$/

/**
 * The commit's per-row field errors (`decisions[<rowId>]`) as rowId → code, first code per row.
 * ROW_UNKNOWN is left out: it means the file changed, which the page handles as a whole.
 */
export function rowErrorsFrom(errors: FieldError[]): Record<string, string> {
  const byRow: Record<string, string> = {}
  for (const { field, code } of errors) {
    const rowId = DECISION_FIELD.exec(field)?.[1]
    if (rowId && code !== 'ROW_UNKNOWN' && !(rowId in byRow)) byRow[rowId] = code
  }
  return byRow
}

/**
 * What each pocket row's goal holds just before the row, walking the moves the commit will record in
 * its order (`applyOrder`: statement time, then file line). Deposits add, withdrawals take out at
 * most what is there, as the backend caps them. Excluded rows get a balance but change nothing;
 * already imported rows are in `savedCents` already. Rows without a goal are left out.
 */
export function goalBalances(
  rows: ReviewRow[],
  goals: { id: string; savedCents: number }[],
): Record<string, number> {
  const held = new Map(goals.map((g) => [g.id, g.savedCents]))
  const balances: Record<string, number> = {}
  const moves = rows
    .filter((r) => r.pocket && r.goalId && !locked(r))
    .sort((a, b) => a.applyOrder - b.applyOrder)
  for (const r of moves) {
    const goalId = r.goalId!
    const before = held.get(goalId) ?? 0
    balances[r.rowId] = before
    if (!r.include) continue
    held.set(
      goalId,
      r.pocket!.direction === 'INTO' ? before + r.amountCents : Math.max(0, before - r.amountCents),
    )
  }
  return balances
}
