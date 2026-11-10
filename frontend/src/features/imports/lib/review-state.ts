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
        r.rowId !== rowId &&
        r.descriptionKey === target.descriptionKey &&
        r.kind === target.kind,
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
    included.length > 0 &&
    included.every((r) => r.kind === 'TRANSFER' || r.categoryId !== null)
  )
}

export function toDecisions(rows: ReviewRow[]): RowDecision[] {
  return rows
    .filter((r) => !locked(r))
    .map((r) => ({
      rowId: r.rowId,
      include: r.include,
      categoryId: r.kind === 'TRANSFER' ? null : r.categoryId,
    }))
}
