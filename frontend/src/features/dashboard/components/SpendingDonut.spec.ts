import { screen, waitFor, within } from '@testing-library/dom'
import { describe, expect, it } from 'vitest'
import type { DashboardResponse } from '@/shared/api/types'
import { render } from '@/test/render'
import SpendingDonut from './SpendingDonut.vue'

type CategorySpend = DashboardResponse['spendingByCategory'][number]
const spending: CategorySpend[] = [
  { categoryId: 'c1', name: 'Mercado', color: '#0ea5e9', icon: 'shopping-cart', totalCents: 45000 },
  { categoryId: 'c2', name: 'Lazer', color: '#db2777', icon: 'gift', totalCents: 12050 },
]
const data = { spendingByCategory: spending } as DashboardResponse
const norm = (s: string | null) => (s ?? '').replace(/\s/g, ' ')

type Option = {
  series: { data: { name: string; value: number; itemStyle: { color: string } }[] }[]
}

describe('SpendingDonut', () => {
  it('spendingDonut_usesCategoryColors', async () => {
    const { wrapper } = await render(SpendingDonut, {}, { props: { data } })
    await waitFor(() => expect(wrapper.findComponent({ name: 'VChart' }).exists()).toBe(true))
    const option = wrapper.findComponent({ name: 'VChart' }).props('option') as Option
    const points = option.series[0]!.data
    expect(points).toHaveLength(2)
    spending.forEach((c, i) => {
      expect(points[i]!.itemStyle.color).toBe(c.color)
      expect(points[i]!.name).toBe(c.name)
      expect(points[i]!.value).toBe(c.totalCents)
    })
  })

  it('legend_listsEachCategoryWithItsAmount', async () => {
    await render(SpendingDonut, {}, { props: { data } })
    const legend = screen.getByRole('list', { name: 'Gastos por categoria' })
    const items = within(legend).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(norm(items[0]!.textContent)).toContain('Mercado')
    expect(norm(items[0]!.textContent)).toContain('€ 450,00')
    expect(norm(items[1]!.textContent)).toContain('€ 120,50')
  })

  it('noSpending_showsMessageInsteadOfChart', async () => {
    const { wrapper } = await render(
      SpendingDonut,
      {},
      { props: { data: { spendingByCategory: [] } as unknown as DashboardResponse } },
    )
    expect(screen.getByText('Nenhuma despesa neste mês.')).toBeTruthy()
    expect(wrapper.findComponent({ name: 'VChart' }).exists()).toBe(false)
  })

  it('loading_showsSkeletonUntilDataArrives', async () => {
    const { wrapper } = await render(SpendingDonut, {}, { props: { data: undefined } })
    expect(wrapper.find('[data-slot="skeleton"]').exists()).toBe(true)
  })
})
