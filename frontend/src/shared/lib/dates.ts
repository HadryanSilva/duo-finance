import type { Locale } from './money'

const pad = (n: number) => String(n).padStart(2, '0')

/** Local calendar date as YYYY-MM-DD. */
export function todayIso(): string {
  const d = new Date()
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

/** Local calendar month as YYYY-MM. */
export function currentMonth(): string {
  return todayIso().slice(0, 7)
}

/** Business dates are built from their parts, never via `new Date('YYYY-MM-DD')` (UTC). */
export function formatBusinessDate(
  iso: string,
  locale: Locale,
  style: 'short' | 'long' = 'short',
): string {
  const [y = 0, m = 1, d = 1] = iso.split('-').map(Number)
  return new Intl.DateTimeFormat(locale, {
    day: 'numeric',
    month: style === 'long' ? 'long' : 'numeric',
    year: 'numeric',
  }).format(new Date(y, m - 1, d))
}

export function formatMonth(month: string, locale: Locale): string {
  const [y = 0, m = 1] = month.split('-').map(Number)
  return new Intl.DateTimeFormat(locale, { month: 'long', year: 'numeric' }).format(
    new Date(y, m - 1, 1),
  )
}

export function addMonths(month: string, n: number): string {
  const [y = 0, m = 1] = month.split('-').map(Number)
  const total = y * 12 + (m - 1) + n
  return `${Math.floor(total / 12)}-${pad((total % 12) + 1)}`
}

export function groupByDate<T extends { date: string }>(rows: T[]): { date: string; rows: T[] }[] {
  const groups = new Map<string, T[]>()
  for (const row of rows) {
    const g = groups.get(row.date)
    if (g) g.push(row)
    else groups.set(row.date, [row])
  }
  return [...groups].map(([date, grouped]) => ({ date, rows: grouped }))
}
