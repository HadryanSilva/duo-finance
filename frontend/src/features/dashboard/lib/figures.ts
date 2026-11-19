import type { DashboardResponse } from '@/shared/api/types'

export interface MonthSummary {
  /** `short` when the month spent more than it received. */
  kind: 'left' | 'short'
  /** Always >= 0: the sign is carried by `kind`. */
  amountCents: number
  /** Whole percent of income left over; null when nothing came in or the month fell short. */
  sharePercent: number | null
}

export function monthSummary(incomeCents: number, expenseCents: number): MonthSummary {
  const diff = incomeCents - expenseCents
  if (diff < 0) return { kind: 'short', amountCents: -diff, sharePercent: null }
  return {
    kind: 'left',
    amountCents: diff,
    sharePercent: incomeCents > 0 ? Math.round((diff * 100) / incomeCents) : null,
  }
}

export interface SpendingSlice {
  /** The category id, or `'others'` for the grouped slice. */
  key: string
  categoryId: string | null
  /** null for the grouped "Outras N" slice. */
  name: string | null
  /** How many categories the grouped slice holds; 0 for a named category. */
  othersCount: number
  /** The category's own color from the API; null for the grouped slice. */
  color: string | null
  totalCents: number
  /** Whole percent of the month's spending. */
  share: number
}

/** Up to this many categories are all listed; past it, the tail is grouped. */
const MAX_LISTED = 6
/** How many named slices stay when the tail is grouped (so the group always holds at least 2). */
const KEPT_WHEN_GROUPED = 5

/** Spending as slices with shares, in the API's order (largest first). */
export function spendingSlices(spending: DashboardResponse['spendingByCategory']): SpendingSlice[] {
  const total = spending.reduce((sum, c) => sum + c.totalCents, 0)
  const share = (cents: number) => (total > 0 ? Math.round((cents * 100) / total) : 0)
  const named = spending.length > MAX_LISTED ? spending.slice(0, KEPT_WHEN_GROUPED) : spending
  const slices: SpendingSlice[] = named.map((c) => ({
    key: c.categoryId,
    categoryId: c.categoryId,
    name: c.name,
    othersCount: 0,
    color: c.color,
    totalCents: c.totalCents,
    share: share(c.totalCents),
  }))
  const rest = spending.slice(named.length)
  if (rest.length) {
    const cents = rest.reduce((sum, c) => sum + c.totalCents, 0)
    slices.push({
      key: 'others',
      categoryId: null,
      name: null,
      othersCount: rest.length,
      color: null,
      totalCents: cents,
      share: share(cents),
    })
  }
  return slices
}

/** "<1%" for a real but tiny share, so it never reads as nothing. */
export function shareLabel(share: number, totalCents: number): string {
  return totalCents > 0 && share < 1 ? '<1%' : `${share}%`
}

/** Days left in `month` (YYYY-MM) counting `today` (YYYY-MM-DD); null when today is not in it. */
export function daysLeftInMonth(month: string, today: string): number | null {
  if (today.slice(0, 7) !== month) return null
  const [year, mon] = month.split('-').map(Number) as [number, number]
  const lastDay = new Date(Date.UTC(year, mon, 0)).getUTCDate()
  return lastDay - Number(today.slice(8, 10)) + 1
}

export function budgetRemainder(
  spentCents: number,
  limitCents: number,
): { over: boolean; amountCents: number } {
  const diff = limitCents - spentCents
  return { over: diff < 0, amountCents: Math.abs(diff) }
}
