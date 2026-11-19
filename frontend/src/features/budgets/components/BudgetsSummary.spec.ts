import { screen } from '@testing-library/dom'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { BudgetResponse } from '@/shared/api/types'
import { render } from '@/test/render'
import BudgetsSummary from './BudgetsSummary.vue'

const norm = (s: string | null | undefined) => (s ?? '').replace(/\s/g, ' ')
const budget = (name: string, spentCents: number, limitCents: number, status: string) =>
  ({
    id: `b-${name}`,
    category: { id: name, name, archived: false, color: '#0ea5e9', icon: 'gift' },
    monthlyLimitCents: limitCents,
    month: '2026-11',
    spentCents,
    percent: Math.floor((spentCents * 100) / limitCents),
    status,
  }) as BudgetResponse

async function mount(budgets: BudgetResponse[], month = '2026-11') {
  const r = await render(BudgetsSummary, {}, { props: { budgets, month } })
  return norm(r.wrapper.text())
}

describe('BudgetsSummary', () => {
  beforeEach(() => {
    vi.useFakeTimers({ toFake: ['Date'] })
    vi.setSystemTime(new Date('2026-11-20T12:00:00'))
  })
  afterEach(() => vi.useRealTimers())

  it('summary_spentOfBudgeted', async () => {
    const text = await mount([
      budget('Mercado', 98000, 100000, 'WARNING'),
      budget('Lazer', 36750, 30000, 'EXCEEDED'),
    ])
    expect(text).toContain('Total do mês')
    expect(text).toContain('€ 1.347,50 de € 1.300,00')
    expect(text).toContain('passou € 47,50 no total')
    expect(text).toContain('1 estourado')
    const bar = screen.getByRole('progressbar', { name: 'Total do mês' })
    expect(bar.getAttribute('data-status')).toBe('EXCEEDED')
  })

  it('summary_under_saysRemaining', async () => {
    const text = await mount([budget('Mercado', 50000, 100000, 'NORMAL')])
    expect(text).toContain('restam € 500,00 no total')
    expect(text).not.toContain('estourado')
    expect(
      screen.getByRole('progressbar', { name: 'Total do mês' }).getAttribute('data-status'),
    ).toBe('NORMAL')
  })

  it('summary_atEightyPercent_isWarning', async () => {
    await mount([budget('Mercado', 80000, 100000, 'WARNING')])
    expect(
      screen.getByRole('progressbar', { name: 'Total do mês' }).getAttribute('data-status'),
    ).toBe('WARNING')
  })

  it('summary_truncatesPercentLikeTheBackend', async () => {
    const text = await mount([budget('Mercado', 99500, 100000, 'WARNING')])
    const bar = screen.getByRole('progressbar', { name: 'Total do mês' })
    expect(bar.getAttribute('data-status')).toBe('WARNING')
    expect(text).toContain('99%')
    expect(text).toContain('restam € 5,00 no total')
  })

  it('summary_currentMonth_daysLeft', async () => {
    expect(await mount([budget('Mercado', 1, 100, 'NORMAL')])).toContain('faltam 11 dias')
  })

  it('summary_pastMonth_noDays', async () => {
    expect(await mount([budget('Mercado', 1, 100, 'NORMAL')], '2026-10')).not.toContain('falta')
  })
})
