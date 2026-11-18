import type { FieldError } from '@/shared/api/problem'
import type { PlannedRow } from '@/shared/api/types'
import type { RowDecision } from '../api/imports-api'

export type ReviewRow = PlannedRow & { include: boolean; categoryId: string | null }

const locked = (r: ReviewRow) => r.status === 'ALREADY_IMPORTED'

export function initReview(rows: PlannedRow[]): ReviewRow[] {
  return rows.map((r) => ({
    ...r,
    include: r.status === 'NEW',
    categoryId: r.suggestedCategoryId,
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

/** An AI suggestion the user has not changed: still worth a look before importing. */
export function isAiSuggested(row: ReviewRow): boolean {
  return row.suggestionSource === 'AI' && row.categoryId === row.suggestedCategoryId
}

export function aiPendingCount(rows: ReviewRow[]): number {
  return rows.filter((r) => r.include && isAiSuggested(r)).length
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
