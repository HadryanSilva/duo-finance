import { describe, expect, it } from 'vitest'
import { render } from '@/test/render'
import MoneyText from './MoneyText.vue'

type Kind = 'INCOME' | 'EXPENSE' | 'TRANSFER' | 'NEUTRAL'

async function money(props: { cents: number; kind?: Kind; signed?: boolean }) {
  const { wrapper } = await render(MoneyText, {}, { props })
  return wrapper.get('span')
}

describe('MoneyText', () => {
  it('moneyText_colorsOnlyIncomeAndExpense', async () => {
    const income = await money({ cents: 123456, kind: 'INCOME' })
    expect(income.classes()).toContain('text-income')
    expect(income.text().replace(/\s/g, ' ')).toBe('+€ 1.234,56')

    const expense = await money({ cents: 500, kind: 'EXPENSE' })
    expect(expense.classes()).toContain('text-expense')
    expect(expense.text()).not.toContain('+')

    for (const kind of ['TRANSFER', 'NEUTRAL', undefined] as const) {
      const other = await money({ cents: 500, kind })
      expect(other.classes()).not.toContain('text-income')
      expect(other.classes()).not.toContain('text-expense')
      expect(other.classes()).toContain('tabular-nums')
    }
  })
})

describe('MoneyText signed', () => {
  it('moneyText_signedNeutral_prefixesPositiveOnly', async () => {
    const up = await money({ cents: 97450, kind: 'NEUTRAL', signed: true })
    expect(up.text().replace(/\s/g, ' ')).toBe('+€ 974,50')
    const down = await money({ cents: -1000, kind: 'NEUTRAL', signed: true })
    expect(down.text().replace(/\s/g, ' ')).toBe('-€ 10,00')
    const zero = await money({ cents: 0, kind: 'NEUTRAL', signed: true })
    expect(zero.text().replace(/\s/g, ' ')).toBe('€ 0,00')
  })
})
