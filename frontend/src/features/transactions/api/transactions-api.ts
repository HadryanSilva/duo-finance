import type { LocationQuery, LocationQueryRaw } from 'vue-router'
import type { TransactionFilter } from '@/shared/api/queries/transactions'
import type { TransactionKind } from '@/shared/api/types'
import { currentMonth } from '@/shared/lib/dates'

export type { TransactionFilter }

export const SEARCH_MAX = 100
export const SEARCH_DEBOUNCE_MS = 300

const KINDS: readonly TransactionKind[] = ['EXPENSE', 'INCOME', 'TRANSFER']
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

/** First and last day (YYYY-MM-DD) of a YYYY-MM month, built from its parts. */
export function monthRange(month: string): { from: string; to: string } {
  const [y = 0, m = 1] = month.split('-').map(Number)
  const last = new Date(y, m, 0).getDate()
  return { from: `${month}-01`, to: `${month}-${String(last).padStart(2, '0')}` }
}

/** Trimmed search text, at most SEARCH_MAX characters. */
export function normalizeSearch(q: string): string {
  return q.trim().slice(0, SEARCH_MAX).trim()
}

function single(value: LocationQuery[string] | undefined): string | undefined {
  const v = Array.isArray(value) ? value[0] : value
  return typeof v === 'string' && v !== '' ? v : undefined
}

/**
 * Reads the filter from the query string. An absent `from`/`to` means the current month;
 * an empty one (`from=`) means no bound. Unknown values are ignored.
 */
export function filterFromQuery(query: LocationQuery): TransactionFilter {
  const month = monthRange(currentMonth())
  const date = (key: 'from' | 'to') => {
    if (!(key in query)) return month[key]
    const v = single(query[key])
    return v && ISO_DATE.test(v) ? v : undefined
  }
  const kind = single(query.kind)
  const q = normalizeSearch(single(query.q) ?? '')
  const filter: TransactionFilter = {
    from: date('from'),
    to: date('to'),
    kind: KINDS.find((k) => k === kind),
    categoryId: single(query.categoryId),
    bankAccountId: single(query.bankAccountId),
    createdBy: single(query.createdBy),
    q: q || undefined,
  }
  // drop undefined keys so the query key and the request stay minimal
  return Object.fromEntries(
    Object.entries(filter).filter(([, v]) => v !== undefined),
  ) as TransactionFilter
}

/** Query-string changes for a filter patch: cleared dates stay as `''`, other cleared keys go away. */
export function queryPatch(patch: Partial<Record<keyof TransactionFilter, string | undefined>>) {
  const out: LocationQueryRaw = {}
  for (const [key, value] of Object.entries(patch)) {
    if (key === 'from' || key === 'to') out[key] = value ?? ''
    else out[key] = value || undefined
  }
  return out
}
