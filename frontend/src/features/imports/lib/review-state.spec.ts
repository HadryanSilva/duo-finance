import { describe, expect, it } from 'vitest'
import type { PlannedRow } from '@/shared/api/types'
import {
  canSubmit,
  initReview,
  rowErrorsFrom,
  sameGroup,
  setCategory,
  setInclude,
  toDecisions,
  totals,
} from './review-state'

function row(over: Partial<PlannedRow> & { rowId: string }): PlannedRow {
  return {
    date: '2026-11-01',
    kind: 'EXPENSE',
    amountCents: 1000,
    description: 'Shop',
    descriptionKey: 'shop',
    bankAccountId: 'acc-1',
    destinationBankAccountId: null,
    suggestedCategoryId: null,
    status: 'NEW',
    ...over,
  }
}

describe('initReview', () => {
  it('includes NEW with suggestion, excludes duplicates and already imported', () => {
    const rows = initReview([
      row({ rowId: 'a', suggestedCategoryId: 'cat-1' }),
      row({ rowId: 'b', status: 'POSSIBLE_DUPLICATE', suggestedCategoryId: 'cat-2' }),
      row({ rowId: 'c', status: 'ALREADY_IMPORTED' }),
    ])
    expect(rows.map((r) => [r.include, r.categoryId])).toEqual([
      [true, 'cat-1'],
      [false, 'cat-2'],
      [false, null],
    ])
  })
})

describe('sameGroup', () => {
  it('sameGroup_ignoresTrailingCardNumbers', () => {
    const rows = initReview([
      row({ rowId: 'a', description: 'UBER *TRIP 4821', descriptionKey: 'uber *trip' }),
      row({ rowId: 'b', description: 'Uber *Trip 9911', descriptionKey: 'uber *trip' }),
      row({
        rowId: 'c',
        kind: 'INCOME',
        description: 'Uber *Trip 1',
        descriptionKey: 'uber *trip',
      }),
      row({ rowId: 'd', description: 'Other', descriptionKey: 'other' }),
    ])
    expect(sameGroup(rows, 'a')).toEqual(['b'])
  })
})

describe('setCategory', () => {
  const base = () =>
    initReview([
      row({ rowId: 'a' }),
      row({ rowId: 'b' }),
      row({ rowId: 'c', descriptionKey: 'x' }),
      row({ rowId: 'd', status: 'ALREADY_IMPORTED' }),
      row({ rowId: 't', kind: 'TRANSFER', destinationBankAccountId: 'acc-2' }),
    ])

  it('sets a single row', () => {
    const out = setCategory(base(), 'a', 'cat', false)
    expect(out.map((r) => r.categoryId)).toEqual(['cat', null, null, null, null])
  })

  it('applies to the group, skipping already imported rows', () => {
    const rows = initReview([
      row({ rowId: 'a' }),
      row({ rowId: 'b' }),
      row({ rowId: 'd', status: 'ALREADY_IMPORTED' }),
    ])
    const out = setCategory(rows, 'a', 'cat', true)
    expect(out.map((r) => r.categoryId)).toEqual(['cat', 'cat', null])
  })

  it('is a no-op for transfers and already imported rows', () => {
    const rows = base()
    expect(setCategory(rows, 't', 'cat', false)).toBe(rows)
    expect(setCategory(rows, 'd', 'cat', true)).toBe(rows)
  })

  it('does not mutate the input', () => {
    const rows = base()
    setCategory(rows, 'a', 'cat', true)
    expect(rows[0]?.categoryId).toBeNull()
  })
})

describe('setInclude', () => {
  it('toggles inclusion and ignores already imported rows', () => {
    const rows = initReview([row({ rowId: 'a' }), row({ rowId: 'd', status: 'ALREADY_IMPORTED' })])
    expect(setInclude(rows, 'a', false)[0]?.include).toBe(false)
    expect(rows[0]?.include).toBe(true)
    expect(setInclude(rows, 'd', true)).toBe(rows)
  })
})

describe('totals', () => {
  it('sums included income/expense and counts transfers', () => {
    const rows = initReview([
      row({ rowId: 'a', kind: 'INCOME', amountCents: 5000 }),
      row({ rowId: 'b', kind: 'EXPENSE', amountCents: 1200 }),
      row({ rowId: 't', kind: 'TRANSFER', amountCents: 900 }),
      row({ rowId: 'x', amountCents: 700, status: 'POSSIBLE_DUPLICATE' }),
    ])
    expect(totals(rows)).toEqual({ count: 3, incomeCents: 5000, expenseCents: 1200 })
  })
})

describe('canSubmit', () => {
  it('canSubmit_falseWithIncludedRowWithoutCategory', () => {
    expect(canSubmit(initReview([row({ rowId: 'a' })]))).toBe(false)
  })
  it('is false with nothing included', () => {
    expect(canSubmit(initReview([row({ rowId: 'a', status: 'POSSIBLE_DUPLICATE' })]))).toBe(false)
  })
  it('is true when included rows are categorised or transfers', () => {
    const rows = initReview([
      row({ rowId: 'a', suggestedCategoryId: 'c' }),
      row({ rowId: 't', kind: 'TRANSFER' }),
    ])
    expect(canSubmit(rows)).toBe(true)
  })
})

describe('toDecisions', () => {
  it('toDecisions_sendsOnlyIncludedRows', () => {
    // a missing decision means "not imported", so excluded rows are left out to keep the body small
    let rows = initReview([
      row({ rowId: 'a', suggestedCategoryId: 'c' }),
      row({ rowId: 'd', status: 'ALREADY_IMPORTED' }),
      row({ rowId: 't', kind: 'TRANSFER', suggestedCategoryId: 'c' }),
      row({ rowId: 'p', status: 'POSSIBLE_DUPLICATE' }),
      row({ rowId: 'q', status: 'POSSIBLE_DUPLICATE', suggestedCategoryId: 'c' }),
      row({ rowId: 'x', suggestedCategoryId: 'c' }),
    ])
    rows = setInclude(rows, 'q', true)
    rows = setInclude(rows, 'x', false)
    expect(toDecisions(rows)).toEqual([
      { rowId: 'a', include: true, categoryId: 'c' },
      { rowId: 't', include: true, categoryId: null },
      { rowId: 'q', include: true, categoryId: 'c' },
    ])
  })
})

describe('rowErrorsFrom', () => {
  it('mapsDecisionFieldsToRowIds_skippingRowUnknownAndOtherFields', () => {
    expect(
      rowErrorsFrom([
        { field: 'decisions[r1]', code: 'CATEGORY_ARCHIVED' },
        { field: 'decisions[r2]', code: 'ROW_UNKNOWN' },
        { field: 'bankAccountId', code: 'NotNull' },
        { field: 'decisions[r1]', code: 'CATEGORY_REQUIRED' },
      ]),
    ).toEqual({ r1: 'CATEGORY_ARCHIVED' })
  })
})
