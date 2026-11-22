import type { RecurringFrequency, TransactionKind } from '@/shared/api/types'

/** Days left in `month` (YYYY-MM) counting `today` (YYYY-MM-DD); null when today is not in it. */
export function daysLeftInMonth(month: string, today: string): number | null {
  if (today.slice(0, 7) !== month) return null
  const [year, mon] = month.split('-').map(Number) as [number, number]
  const lastDay = new Date(Date.UTC(year, mon, 0)).getUTCDate()
  return lastDay - Number(today.slice(8, 10)) + 1
}

/** What is left of a budget, or by how much it went over; the amount is never negative. */
export function budgetRemainder(
  spentCents: number,
  limitCents: number,
): { over: boolean; amountCents: number } {
  const diff = limitCents - spentCents
  return { over: diff < 0, amountCents: Math.abs(diff) }
}

/** Months in a year over occurrences in a year, per frequency. */
const PER_MONTH: Record<RecurringFrequency, number> = {
  WEEKLY: 52 / 12,
  MONTHLY: 1,
  YEARLY: 1 / 12,
}

/** What a recurring amount weighs per month, in whole cents. */
export function monthlyEquivalentCents(amountCents: number, frequency: RecurringFrequency): number {
  return Math.round(amountCents * PER_MONTH[frequency])
}

/**
 * Fixed monthly inflow and outflow of recurring rules; transfers only move money between own
 * accounts and are left out. In and out are rounded once, after summing the exact equivalents.
 */
export function fixedMonthly(
  rules: { kind: TransactionKind; amountCents: number; frequency: RecurringFrequency }[],
): { inCents: number; outCents: number; netCents: number } {
  let income = 0
  let expense = 0
  for (const r of rules) {
    const monthly = r.amountCents * PER_MONTH[r.frequency]
    if (r.kind === 'INCOME') income += monthly
    else if (r.kind === 'EXPENSE') expense += monthly
  }
  const inCents = Math.round(income)
  const outCents = Math.round(expense)
  // Derived from the rounded totals, so the three figures on screen always add up.
  return { inCents, outCents, netCents: inCents - outCents }
}

/** "<1%" for a real but tiny share, so it never reads as nothing. */
export function shareLabel(share: number, totalCents: number): string {
  return totalCents > 0 && share < 1 ? '<1%' : `${share}%`
}
