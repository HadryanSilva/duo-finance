import { screen, waitFor } from '@testing-library/dom'
import { afterEach, describe, expect, it } from 'vitest'
import type { DashboardResponse } from '@/shared/api/types'
import { render } from '@/test/render'
import IncomeExpenseChart from './IncomeExpenseChart.vue'

const data = { incomeCents: 500000, expenseCents: 123456 } as DashboardResponse
type Option = { series: { data: { value: number; itemStyle: { color: string } }[] }[] }

const root = document.documentElement
afterEach(() => {
  root.style.removeProperty('--color-income')
  root.style.removeProperty('--color-expense')
  root.classList.remove('dark')
})

async function mountChart() {
  const r = await render(IncomeExpenseChart, {}, { props: { data } })
  await waitFor(() => expect(r.wrapper.findComponent({ name: 'VChart' }).exists()).toBe(true))
  const option = () => r.wrapper.findComponent({ name: 'VChart' }).props('option') as Option
  return { ...r, option }
}

describe('IncomeExpenseChart', () => {
  it('bars_useIncomeAndExpenseTokens', async () => {
    root.style.setProperty('--color-income', 'lime')
    root.style.setProperty('--color-expense', 'maroon')
    const { option } = await mountChart()
    const [income, expense] = option().series[0]!.data
    expect(income).toMatchObject({ value: 500000, itemStyle: { color: 'lime' } })
    expect(expense).toMatchObject({ value: 123456, itemStyle: { color: 'maroon' } })
  })

  it('theme_rereadsTokensWhenTheDarkClassChanges', async () => {
    root.style.setProperty('--color-income', 'lime')
    const { option } = await mountChart()
    expect(option().series[0]!.data[0]!.itemStyle.color).toBe('lime')
    root.style.setProperty('--color-income', 'aqua')
    root.classList.add('dark')
    await waitFor(() => expect(option().series[0]!.data[0]!.itemStyle.color).toBe('aqua'))
  })

  it('totals_showIncomeAndExpense', async () => {
    await mountChart()
    const text = document.body.textContent?.replace(/\s/g, ' ') ?? ''
    expect(text).toContain('Receitas')
    expect(text).toContain('€ 5.000,00')
    expect(text).toContain('Despesas')
    expect(text).toContain('€ 1.234,56')
    expect(screen.getByRole('img', { name: /Gráfico de barras/ })).toBeTruthy()
  })
})
