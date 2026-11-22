import type { MonthReviewResponse } from '@/shared/api/types'

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
}

const byName = (a: ReviewRow, b: ReviewRow) =>
  a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })

/** What changed most comes first; without a comparison, what cost most. */
export function reviewRows(categories: MonthReviewResponse['categories']): ReviewRow[] {
  return categories
    .map((c) => ({
      ...c,
      differenceCents: c.averageCents === null ? null : c.cents - c.averageCents,
      tone: differenceTone(c.cents, c.averageCents, false),
    }))
    .sort(
      (a, b) =>
        Math.abs(b.differenceCents ?? 0) - Math.abs(a.differenceCents ?? 0) ||
        b.cents - a.cents ||
        byName(a, b),
    )
}

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/

/** The month a link asks for, kept within the months that can be reviewed. */
export function clampMonth(
  wanted: unknown,
  firstMonth: string | null,
  lastClosedMonth: string,
): string {
  if (firstMonth === null || typeof wanted !== 'string' || !MONTH.test(wanted))
    return lastClosedMonth
  if (wanted < firstMonth) return firstMonth
  if (wanted > lastClosedMonth) return lastClosedMonth
  return wanted
}
