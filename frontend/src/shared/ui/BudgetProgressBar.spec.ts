import { describe, expect, it } from 'vitest'
import { render } from '@/test/render'
import BudgetProgressBar from './BudgetProgressBar.vue'

type Status = 'NORMAL' | 'WARNING' | 'EXCEEDED'

async function bar(percent: number, status: Status = 'NORMAL') {
  const { wrapper } = await render(
    BudgetProgressBar,
    {},
    { props: { spentCents: 15000, limitCents: 10000, percent, status } },
  )
  return wrapper
}

describe('BudgetProgressBar', () => {
  it('budgetBar_capsWidthButShowsRealPercent', async () => {
    const wrapper = await bar(150, 'EXCEEDED')
    const progress = wrapper.get('[role="progressbar"]')
    expect(progress.attributes('aria-valuenow')).toBe('100')
    expect(progress.attributes('aria-valuemin')).toBe('0')
    expect(progress.attributes('aria-valuemax')).toBe('100')
    expect(progress.attributes('aria-valuetext')).toBe('150% do limite')
    expect((progress.get('div').element as HTMLElement).style.width).toBe('100%')
    expect(wrapper.text()).toContain('150%')
  })

  it('budgetBar_colorsByStatus', async () => {
    const fill = async (status: Status) =>
      (await bar(50, status)).get('[role="progressbar"] > div').classes()
    expect(await fill('NORMAL')).toContain('bg-brand')
    expect(await fill('WARNING')).toContain('bg-warning')
    expect(await fill('EXCEEDED')).toContain('bg-expense')
  })

  it('budgetBar_partialWidth', async () => {
    const wrapper = await bar(42)
    const fill = wrapper.get('[role="progressbar"] > div').element as HTMLElement
    expect(fill.style.width).toBe('42%')
  })
})
