import { describe, expect, it } from 'vitest'
import {
  budgetRemainder,
  daysLeftInMonth,
  moneyLabel,
  monthSummary,
  shareLabel,
  spendingSlices,
} from './figures'

describe('monthSummary', () => {
  it('left_withShare', () =>
    expect(monthSummary(320000, 245000)).toEqual({
      kind: 'left',
      amountCents: 75000,
      sharePercent: 23,
    }))
  it('expenseOverIncome_isShortWithPositiveAmount', () =>
    expect(monthSummary(100000, 120000)).toEqual({
      kind: 'short',
      amountCents: 20000,
      sharePercent: null,
    }))
  it('zeroIncome_hasNoShare', () =>
    expect(monthSummary(0, 0)).toEqual({ kind: 'left', amountCents: 0, sharePercent: null }))
  it('zeroIncomeWithExpense_isShort', () =>
    expect(monthSummary(0, 5000)).toEqual({ kind: 'short', amountCents: 5000, sharePercent: null }))
})

describe('spendingSlices', () => {
  const cat = (i: number, cents: number) => ({
    categoryId: `c${i}`,
    name: `C${i}`,
    color: '#000000',
    icon: 'gift',
    totalCents: cents,
  })
  it('sixOrFewer_allListed', () => {
    const s = spendingSlices([1, 2, 3, 4, 5, 6].map((i) => cat(i, 1000)))
    expect(s).toHaveLength(6)
    expect(s.every((x) => x.othersCount === 0)).toBe(true)
  })
  it('sevenOrMore_fivePlusOthers', () => {
    const s = spendingSlices([1, 2, 3, 4, 5, 6, 7].map((i) => cat(i, 1000)))
    expect(s).toHaveLength(6)
    expect(s[0]).toMatchObject({ key: 'c1', categoryId: 'c1', name: 'C1', color: '#000000' })
    expect(s[5]).toMatchObject({
      key: 'others',
      categoryId: null,
      name: null,
      color: null,
      othersCount: 2,
      totalCents: 2000,
      share: 29,
    })
  })
  it('shares_areRoundedPercentOfTotal', () =>
    expect(spendingSlices([cat(1, 45000), cat(2, 12050)]).map((x) => x.share)).toEqual([79, 21]))
  it('keepsInputOrder', () =>
    expect(spendingSlices([cat(1, 10), cat(2, 9000)]).map((x) => x.key)).toEqual(['c1', 'c2']))
})

describe('shareLabel', () => {
  it('underOnePercent', () => expect(shareLabel(0, 10)).toBe('<1%'))
  it('normal', () => expect(shareLabel(40, 98000)).toBe('40%'))
})

describe('daysLeftInMonth', () => {
  it('countsToday', () => expect(daysLeftInMonth('2026-11', '2026-11-20')).toBe(11))
  it('lastDayIsOne', () => expect(daysLeftInMonth('2026-02', '2026-02-28')).toBe(1))
  it('firstDayCountsWholeMonth', () => expect(daysLeftInMonth('2026-12', '2026-12-01')).toBe(31))
  it('otherMonth_isNull', () => expect(daysLeftInMonth('2026-10', '2026-11-20')).toBeNull())
})

describe('budgetRemainder', () => {
  it('under', () =>
    expect(budgetRemainder(98000, 100000)).toEqual({ over: false, amountCents: 2000 }))
  it('exactlyAtLimit_isNotOver', () =>
    expect(budgetRemainder(100000, 100000)).toEqual({ over: false, amountCents: 0 }))
  it('over', () => expect(budgetRemainder(36750, 30000)).toEqual({ over: true, amountCents: 6750 }))
})

describe('moneyLabel', () => {
  it('formatsForTheLocale', () =>
    expect(moneyLabel(45000, 'pt-BR').replace(/\s/g, ' ')).toBe('€ 450,00'))
})
