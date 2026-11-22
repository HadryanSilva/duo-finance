import { describe, expect, it } from 'vitest'
import type { MonthReviewResponse } from '@/shared/api/types'
import { clampMonth, differenceTone, reviewRows } from './review'

type Line = MonthReviewResponse['categories'][number]

const line = (name: string, cents: number, averageCents: number | null): Line => ({
  categoryId: `id-${name}`,
  name,
  color: '#000000',
  icon: 'tag',
  archived: false,
  cents,
  averageCents,
})

describe('differenceTone', () => {
  it('expense_aboveAverage_isWorse', () => {
    expect(differenceTone(1000, 600, false)).toBe('worse')
  })

  it('expense_belowAverage_isBetter', () => {
    expect(differenceTone(500, 600, false)).toBe('better')
  })

  it('underOneEuro_isSame', () => {
    expect(differenceTone(650, 600, true)).toBe('same')
    expect(differenceTone(501, 600, false)).toBe('same')
  })

  it('income_aboveAverage_isBetter', () => {
    expect(differenceTone(700, 600, true)).toBe('better')
    expect(differenceTone(500, 600, true)).toBe('worse')
  })

  it('noAverage_isNull', () => {
    expect(differenceTone(700, null, true)).toBeNull()
  })
})

describe('reviewRows', () => {
  it('ordersByLargestChange_thenByMonthAmount', () => {
    const rows = reviewRows([
      line('D', 8000, 14000),
      line('C', 90000, 90000),
      line('B', 21000, 12000),
      line('A', 52000, 43000),
    ])
    expect(rows.map((r) => r.name)).toEqual(['A', 'B', 'D', 'C'])
    expect(rows[0]).toMatchObject({ differenceCents: 9000, tone: 'worse' })
    expect(rows[2]).toMatchObject({ differenceCents: -6000, tone: 'better' })
    expect(rows[3]).toMatchObject({ differenceCents: 0, tone: 'same' })
  })

  it('withoutComparison_ordersByAmountThenName', () => {
    const rows = reviewRows([line('b', 100, null), line('C', 300, null), line('a', 100, null)])
    expect(rows.map((r) => r.name)).toEqual(['C', 'a', 'b'])
    expect(rows[0]).toMatchObject({ differenceCents: null, tone: null })
  })

  it('equalChangeAndAmount_ordersByNameIgnoringCase', () => {
    const rows = reviewRows([line('beta', 20000, 10000), line('Alpha', 20000, 10000)])
    expect(rows.map((r) => r.name)).toEqual(['Alpha', 'beta'])
  })
})

describe('clampMonth', () => {
  it('invalid_fallsBackToLastClosed', () => {
    expect(clampMonth('garbage', '2026-11', '2027-02')).toBe('2027-02')
    expect(clampMonth(['2026-12'], '2026-11', '2027-02')).toBe('2027-02')
    expect(clampMonth(undefined, '2026-11', '2027-02')).toBe('2027-02')
    expect(clampMonth('2026-13', '2026-11', '2027-02')).toBe('2027-02')
  })

  it('outOfRange_isClamped', () => {
    expect(clampMonth('2030-01', '2026-11', '2027-02')).toBe('2027-02')
    expect(clampMonth('2026-01', '2026-11', '2027-02')).toBe('2026-11')
  })

  it('inRange_isKept', () => {
    expect(clampMonth('2026-12', '2026-11', '2027-02')).toBe('2026-12')
  })

  it('noFirstMonth_isLastClosed', () => {
    expect(clampMonth('2026-12', null, '2027-02')).toBe('2027-02')
  })
})

describe('clampMonth before any month is over', () => {
  it('firstMonthAfterLastClosed_isLastClosed', () => {
    expect(clampMonth('2026-10', '2026-11', '2026-10')).toBe('2026-10')
    expect(clampMonth(undefined, '2026-11', '2026-10')).toBe('2026-10')
  })
})
