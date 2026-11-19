import { describe, expect, it } from 'vitest'
import type { RecurringRuleResponse } from '@/shared/api/types'
import { render } from '@/test/render'
import RecurringSummary from './RecurringSummary.vue'

const norm = (s: string | null | undefined) => (s ?? '').replace(/\s/g, ' ')
const rule = (kind: string, amountCents: number, frequency: string) =>
  ({ id: `${kind}-${amountCents}`, kind, amountCents, frequency }) as RecurringRuleResponse

async function mount(rules: RecurringRuleResponse[]) {
  const { wrapper } = await render(RecurringSummary, {}, { props: { rules } })
  return wrapper
}

describe('RecurringSummary', () => {
  it('summary_inOutLeft', async () => {
    const wrapper = await mount([
      rule('INCOME', 320000, 'MONTHLY'),
      rule('EXPENSE', 95000, 'MONTHLY'),
      rule('EXPENSE', 4000, 'WEEKLY'),
      rule('TRANSFER', 50000, 'MONTHLY'),
    ])
    const text = norm(wrapper.text())
    expect(text).toContain('Entradas fixas')
    expect(text).toContain('€ 3.200,00')
    expect(text).toContain('€ 1.123,33')
    expect(text).toContain('Sobra fixa por mês')
    expect(text).toContain('€ 2.076,67')
  })

  it('summary_short_whenOutExceedsIn', async () => {
    const wrapper = await mount([rule('INCOME', 1000, 'MONTHLY'), rule('EXPENSE', 3000, 'MONTHLY')])
    expect(norm(wrapper.text())).toContain('Falta fixa por mês')
    expect(norm(wrapper.text())).toContain('€ 20,00')
    expect(wrapper.find('[data-testid="fixed-net"]').classes()).toContain('text-expense')
  })

  it('summary_noFixedIncome_hidesShort', async () => {
    const wrapper = await mount([rule('EXPENSE', 95000, 'MONTHLY')])
    const text = norm(wrapper.text())
    expect(text).toContain('Saídas fixas')
    expect(text).toContain('€ 950,00')
    expect(text).not.toContain('Falta fixa')
    expect(wrapper.find('[data-testid="fixed-net"]').exists()).toBe(false)
  })

  it('summary_onlyTransfers_rendersNothing', async () => {
    const wrapper = await mount([rule('TRANSFER', 50000, 'MONTHLY')])
    expect(wrapper.text()).toBe('')
  })
})
