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
const month = '2026-03'
const norm = (s: string | null) => (s ?? '').replace(/\s/g, ' ')

type Option = {
  series: { data: { name: string; value: number; itemStyle: { color: string } }[] }[]
}

describe('SpendingDonut', () => {
  it('spendingDonut_usesCategoryColors', async () => {
    const { wrapper } = await render(SpendingDonut, {}, { props: { data, month } })
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
    await render(SpendingDonut, {}, { props: { data, month } })
    const legend = screen.getByRole('list', { name: 'Gastos por categoria' })
    const items = within(legend).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(norm(items[0]!.textContent)).toContain('Mercado')
    expect(norm(items[0]!.textContent)).toContain('€ 450,00')
    expect(norm(items[0]!.textContent)).toContain('79%')
    expect(norm(items[1]!.textContent)).toContain('€ 120,50')
    expect(norm(items[1]!.textContent)).toContain('21%')
  })

  it('title_saysWhereTheMoneyWent', async () => {
    await render(SpendingDonut, {}, { props: { data, month } })
    expect(screen.getByText('Para onde foi o dinheiro')).toBeTruthy()
  })

  it('legend_rowLinksToTransactionsOfThatCategoryAndMonth', async () => {
    await render(SpendingDonut, {}, { props: { data, month } })
    const link = screen.getByRole('link', { name: 'Ver lançamentos de Mercado (79%)' })
    expect(link.getAttribute('href')).toBe(
      '/transactions?from=2026-03-01&to=2026-03-31&categoryId=c1',
    )
  })

  it('legend_sevenCategories_groupsOthers', async () => {
    const seven = [1, 2, 3, 4, 5, 6, 7].map((i) => ({
      categoryId: 'c' + i,
      name: 'Cat ' + i,
      color: '#0ea5e9',
      icon: 'gift',
      totalCents: 1000,
    }))
    const { wrapper } = await render(
      SpendingDonut,
      {},
      { props: { data: { spendingByCategory: seven } as DashboardResponse, month } },
    )
    const items = within(screen.getByRole('list', { name: 'Gastos por categoria' })).getAllByRole(
      'listitem',
    )
    expect(items).toHaveLength(6)
    expect(norm(items[5]!.textContent)).toContain('Outras 2')
    expect(items[5]!.querySelector('a')).toBeNull()
    await waitFor(() => expect(wrapper.findComponent({ name: 'VChart' }).exists()).toBe(true))
    const option = wrapper.findComponent({ name: 'VChart' }).props('option') as Option
    expect(option.series[0]!.data).toHaveLength(6)
    expect(option.series[0]!.data[5]!.name).toBe('Outras 2')
  })

  it('noSpending_showsMessageInsteadOfChart', async () => {
    const { wrapper } = await render(
      SpendingDonut,
      {},
      { props: { data: { spendingByCategory: [] } as unknown as DashboardResponse, month } },
    )
    expect(screen.getByText('Nenhuma despesa neste mês.')).toBeTruthy()
    const cta = screen.getByRole('link', { name: 'Registrar despesa' })
    expect(cta.getAttribute('href')).toBe('/transactions?new=1')
    expect(wrapper.findComponent({ name: 'VChart' }).exists()).toBe(false)
  })

  it('loading_showsSkeletonUntilDataArrives', async () => {
    const { wrapper } = await render(SpendingDonut, {}, { props: { data: undefined, month } })
    expect(wrapper.find('[data-slot="skeleton"]').exists()).toBe(true)
  })
})
