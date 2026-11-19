import { describe, expect, it } from 'vitest'
import { budgetRemainder, daysLeftInMonth, fixedMonthly, monthlyEquivalentCents } from './figures'

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

describe('monthlyEquivalentCents', () => {
  it('monthlyEquivalent_weekly', () => expect(monthlyEquivalentCents(4000, 'WEEKLY')).toBe(17333))
  it('monthlyEquivalent_yearly', () => expect(monthlyEquivalentCents(36000, 'YEARLY')).toBe(3000))
  it('monthlyEquivalent_monthly', () =>
    expect(monthlyEquivalentCents(95000, 'MONTHLY')).toBe(95000))
})

describe('fixedMonthly', () => {
  it('fixedMonthly_sumsByKindIgnoringTransfers', () =>
    expect(
      fixedMonthly([
        { kind: 'INCOME', amountCents: 320000, frequency: 'MONTHLY' },
        { kind: 'EXPENSE', amountCents: 95000, frequency: 'MONTHLY' },
        { kind: 'EXPENSE', amountCents: 4000, frequency: 'WEEKLY' },
        { kind: 'TRANSFER', amountCents: 50000, frequency: 'MONTHLY' },
      ]),
    ).toEqual({ inCents: 320000, outCents: 112333, netCents: 207667 }))

  it('fixedMonthly_roundsOnce', () =>
    expect(
      fixedMonthly(
        [1, 2, 3].map(() => ({
          kind: 'EXPENSE' as const,
          amountCents: 1,
          frequency: 'WEEKLY' as const,
        })),
      ).outCents,
    ).toBe(13))
})
