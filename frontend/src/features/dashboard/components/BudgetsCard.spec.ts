import { screen } from '@testing-library/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { DashboardResponse } from '@/shared/api/types'
import { render } from '@/test/render'
import BudgetsCard from './BudgetsCard.vue'

const norm = (s: string | null) => (s ?? '').replace(/\s/g, ' ')
const category = (name: string) => ({
  id: name,
  name,
  archived: false,
  color: '#0ea5e9',
  icon: 'shopping-cart',
})
const budget = (name: string, spentCents: number, limitCents: number, status = 'NORMAL') => ({
  id: `b-${name}`,
  category: category(name),
  monthlyLimitCents: limitCents,
  month: '2026-11',
  spentCents,
  percent: Math.round((spentCents * 100) / limitCents),
  status,
})
const withBudgets = (...budgets: ReturnType<typeof budget>[]) =>
  ({ budgets }) as unknown as DashboardResponse

async function mount(data: DashboardResponse, month = '2026-11') {
  const r = await render(BudgetsCard, {}, { props: { data, month } })
  return { ...r, text: norm(r.wrapper.text()) }
}

describe('BudgetsCard', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-20T12:00:00'))
  })
  afterEach(() => vi.useRealTimers())

  it('currentMonth_showsDaysLeft', async () => {
    const { text } = await mount(withBudgets(budget('Mercado', 1000, 2000)))
    expect(text).toContain('faltam 11 dias')
  })

  it('lastDay_saysLastDay', async () => {
    vi.setSystemTime(new Date('2026-11-30T12:00:00'))
    const { text } = await mount(withBudgets(budget('Mercado', 1000, 2000)))
    expect(text).toContain('último dia do mês')
    expect(text).not.toContain('falta')
  })

  it('pastMonth_hidesDaysLeft', async () => {
    const { text } = await mount(withBudgets(budget('Mercado', 1000, 2000)), '2026-10')
    expect(text).not.toContain('falta')
    expect(text).not.toContain('último dia')
  })

  it('underLimit_showsRemaining', async () => {
    const { text } = await mount(withBudgets(budget('Mercado', 98000, 100000)))
    expect(text).toContain('restam € 20,00')
    expect(text).toContain('€ 980,00 de € 1.000,00')
  })

  it('overLimit_showsOverInExpenseColor', async () => {
    await mount(withBudgets(budget('Lazer', 36750, 30000, 'EXCEEDED')))
    const over = screen.getByText(
      (_, el) => el?.tagName === 'P' && norm(el.textContent) === 'passou € 67,50',
    )
    expect(over.classList).toContain('text-expense')
  })

  it('warning_showsRemainingInWarningColor', async () => {
    await mount(withBudgets(budget('Mercado', 85000, 100000, 'WARNING')))
    const left = screen.getByText(
      (_, el) => el?.tagName === 'P' && norm(el.textContent) === 'restam € 150,00',
    )
    expect(left.classList).toContain('text-warning')
  })

  it('exceededAtLimit_showsRemainingInExpenseColor', async () => {
    await mount(withBudgets(budget('Mercado', 100000, 100000, 'EXCEEDED')))
    const left = screen.getByText(
      (_, el) => el?.tagName === 'P' && norm(el.textContent) === 'restam € 0,00',
    )
    expect(left.classList).toContain('text-expense')
  })

  it('empty_offersCta', async () => {
    await mount(withBudgets())
    expect(screen.getByRole('link', { name: 'Definir orçamento' }).getAttribute('href')).toBe(
      '/budgets',
    )
  })
})
