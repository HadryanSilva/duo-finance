import type { MonthReviewResponse } from '@/shared/api/types'
import { shareLabel } from '@/shared/lib/figures'

/** A difference smaller than this (in absolute value) reads as "no change". */
export const SMALL_DIFFERENCE_CENTS = 100

/** Whether a figure moved the good way, the bad way, or barely moved. */
export type Tone = 'worse' | 'better' | 'same'

/**
 * Colour by meaning, not by sign: for income and what was left more is good, for spending more is bad.
 * Null without an average to compare with.
 */
export function differenceTone(
  cents: number,
  averageCents: number | null,
  moreIsGood: boolean,
): Tone | null {
  if (averageCents === null) return null
  const difference = cents - averageCents
  if (Math.abs(difference) < SMALL_DIFFERENCE_CENTS) return 'same'
  return difference > 0 === moreIsGood ? 'better' : 'worse'
}

export interface ReviewRow {
  categoryId: string
  name: string
  color: string
  icon: string
  archived: boolean
  cents: number
  averageCents: number | null
  differenceCents: number | null
  tone: Tone | null
  /** Whole percent of the month's spending. */
  share: number
}

/** 'size': what cost most first; 'change': what moved most against the average first. */
export type ReviewOrder = 'size' | 'change'

const byName = (a: ReviewRow, b: ReviewRow) =>
  a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })

const bySize = (a: ReviewRow, b: ReviewRow) => b.cents - a.cents || byName(a, b)
const byChange = (a: ReviewRow, b: ReviewRow) =>
  Math.abs(b.differenceCents ?? 0) - Math.abs(a.differenceCents ?? 0) || bySize(a, b)

/** The month's categories with their difference and share, in the chosen order. */
export function reviewRows(
  categories: MonthReviewResponse['categories'],
  expenseCents: number,
  order: ReviewOrder,
): ReviewRow[] {
  return categories
    .map((c) => ({
      ...c,
      differenceCents: c.averageCents === null ? null : c.cents - c.averageCents,
      tone: differenceTone(c.cents, c.averageCents, false),
      // Rounded like the dashboard donut's shares.
      share: expenseCents > 0 ? Math.round((c.cents * 100) / expenseCents) : 0,
    }))
    .sort(order === 'size' ? bySize : byChange)
}

/** A share as shown: "—" for a category that dropped to nothing, "<1%" for a real but tiny one. */
export function shareText(row: Pick<ReviewRow, 'cents' | 'share'>): string {
  return row.cents === 0 ? '—' : shareLabel(row.share, row.cents)
}

/**
 * A budget to start from: the usual (the average) when there is one, otherwise what this month cost, rounded up to a
 * whole euro. The source is shown next to it, so it reads as a reference, not as advice.
 */
export function suggestedLimit(row: Pick<ReviewRow, 'cents' | 'averageCents'>): {
  cents: number
  source: 'average' | 'month'
} {
  const useAverage = row.averageCents !== null && row.averageCents > 0
  const base = useAverage ? row.averageCents! : row.cents
  return { cents: Math.ceil(base / 100) * 100, source: useAverage ? 'average' : 'month' }
}

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/

/** The month a link asks for, kept within the months that can be reviewed. */
export function clampMonth(
  wanted: unknown,
  firstMonth: string | null,
  lastClosedMonth: string,
): string {
  if (
    firstMonth === null ||
    !hasClosedMonth(firstMonth, lastClosedMonth) ||
    typeof wanted !== 'string' ||
    !MONTH.test(wanted)
  )
    return lastClosedMonth
  if (wanted < firstMonth) return firstMonth
  if (wanted > lastClosedMonth) return lastClosedMonth
  return wanted
}

/** Whether any month with transactions is over yet: in a couple's first month, none is. */
export function hasClosedMonth(firstMonth: string | null, lastClosedMonth: string): boolean {
  return firstMonth !== null && firstMonth <= lastClosedMonth
}
