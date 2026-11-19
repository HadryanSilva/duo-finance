import { describe, expect, it } from 'vitest'
import type { DashboardResponse } from '@/shared/api/types'
import { render } from '@/test/render'
import MonthSummaryCard from './MonthSummaryCard.vue'

const norm = (s: string | null) => (s ?? '').replace(/\s/g, ' ')
const totals = (incomeCents: number, expenseCents: number) =>
  ({ incomeCents, expenseCents }) as DashboardResponse

async function textOf(data: DashboardResponse | undefined) {
  const { wrapper } = await render(MonthSummaryCard, {}, { props: { data } })
  return { wrapper, text: norm(wrapper.text()) }
}

describe('MonthSummaryCard', () => {
  it('summary_showsInOutAndLeftWithShare', async () => {
    const { text } = await textOf(totals(320000, 245000))
    expect(text).toContain('Entrou')
    expect(text).toContain('€ 3.200,00')
    expect(text).toContain('Saiu')
    expect(text).toContain('€ 2.450,00')
    expect(text).toContain('Sobrou')
    expect(text).toContain('€ 750,00')
    expect(text).toContain('23% do que entrou')
  })

  it('summary_expenseOverIncome_saysShort', async () => {
    const { text } = await textOf(totals(100000, 120000))
    expect(text).toContain('Faltou')
    expect(text).toContain('€ 200,00')
    expect(text).not.toContain('Sobrou')
    expect(text).not.toContain('do que entrou')
  })

  it('summary_zeroIncome_hidesShare', async () => {
    const { text } = await textOf(totals(0, 0))
    expect(text).not.toContain('%')
  })

  it('loading_showsSkeletons', async () => {
    const { wrapper } = await textOf(undefined)
    expect(wrapper.findAll('[data-slot="skeleton"]')).toHaveLength(3)
  })
})
