import { screen, waitFor, within } from '@testing-library/dom'
import { http, HttpResponse, type RequestHandler } from 'msw'
import { describe, expect, it } from 'vitest'
import App from '@/app/App.vue'
import { addMonths, currentMonth } from '@/shared/lib/dates'
import { render, renderRoute } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, detail: 'raw' }, { status })

const cat = { color: '#0000aa', systemDefault: false, archived: false }
const mercado = { ...cat, id: 'c1', name: 'Mercado', kind: 'EXPENSE', icon: 'shopping-cart' }
const lazer = { ...cat, id: 'c2', name: 'Lazer', kind: 'EXPENSE', icon: 'gift' }
const transporte = { ...cat, id: 'c3', name: 'Transporte', kind: 'EXPENSE', icon: 'car' }
const antiga = { ...cat, id: 'c4', name: 'Antiga', kind: 'EXPENSE', icon: 'gift', archived: true }
const categories = [mercado, lazer, transporte, antiga]

const ref = (c: typeof mercado) => ({
  id: c.id,
  name: c.name,
  archived: c.archived,
  color: c.color,
  icon: c.icon,
})
const budget = (
  id: string,
  c: typeof mercado,
  limit: number,
  spent: number,
  percent: number,
  status: string,
) => ({
  id,
  category: ref(c),
  monthlyLimitCents: limit,
  month: currentMonth(),
  spentCents: spent,
  percent,
  status,
})
const b1 = budget('b1', mercado, 10000, 15000, 150, 'EXCEEDED')
const b2 = budget('b2', lazer, 20000, 5000, 25, 'NORMAL')

function setup(extra: RequestHandler[] = []) {
  const months: string[] = []
  const calls: Record<string, unknown> = {}
  const handlers = [
    ...extra,
    http.get(`${api}/categories`, ({ request }) => {
      const p = new URL(request.url).searchParams
      const archived = p.get('includeArchived') === 'true'
      const kind = p.get('kind')
      return HttpResponse.json(
        categories.filter((c) => (!kind || c.kind === kind) && (archived || !c.archived)),
      )
    }),
    http.get(`${api}/budgets`, ({ request }) => {
      months.push(new URL(request.url).searchParams.get('month') ?? '')
      return HttpResponse.json([b1, b2])
    }),
    http.post(`${api}/budgets`, async ({ request }) => {
      calls.post = await request.json()
      return HttpResponse.json(b1, { status: 201 })
    }),
    http.put(`${api}/budgets/:id`, async ({ request }) => {
      calls.put = await request.json()
      return HttpResponse.json(b1)
    }),
    http.delete(`${api}/budgets/:id`, ({ params }) => {
      calls.deleted = params.id
      return new HttpResponse(null, { status: 204 })
    }),
  ]
  return {
    months,
    calls,
    render: (route: string) => renderRoute({ route, session: {}, handlers }),
    // renderRoute waits for the page heading, which an open side panel hides from the a11y tree
    mount: (route: string) => render(App, { route, session: {}, handlers }),
  }
}

const optionTexts = (select: HTMLElement) =>
  Array.from((select as HTMLSelectElement).options).map((o) => o.text.trim())

const findList = () => screen.findByRole('list', { name: 'Orçamentos do mês' })

describe('BudgetsPage', () => {
  it('list_showsBarsPerBudget', async () => {
    await setup().render('/budgets')
    const items = within(await findList()).getAllByRole('listitem')
    expect(items).toHaveLength(2)
    const bar = within(items[0]!).getByRole('progressbar', { name: 'Mercado' })
    expect(bar.getAttribute('aria-valuenow')).toBe('100')
    expect(items[0]!.textContent).toContain('150%')
    expect(items[0]!.textContent?.replace(/\s/g, ' ')).toContain('€ 150,00 de € 100,00')
  })

  it('budgets_monthSelectorRefetches', async () => {
    const s = setup()
    const { router, user } = await s.render('/budgets')
    await waitFor(() => expect(s.months).toContain(currentMonth()))
    await user.click(screen.getByRole('button', { name: 'Mês anterior' }))
    const prev = addMonths(currentMonth(), -1)
    await waitFor(() => expect(router.currentRoute.value.query.month).toBe(prev))
    await waitFor(() => expect(s.months).toContain(prev))
    await user.click(screen.getByRole('button', { name: 'Próximo mês' }))
    await waitFor(() => expect(router.currentRoute.value.query.month).toBe(currentMonth()))
  })

  it('budgets_monthFromUrl', async () => {
    const s = setup()
    await s.render('/budgets?month=2026-03')
    await waitFor(() => expect(s.months).toEqual(['2026-03']))
    expect(screen.getByText('Março de 2026')).toBeTruthy()
  })

  it('budgets_createOffersOnlyExpenseCategoriesWithoutBudget', async () => {
    const s = setup()
    const { user } = await s.mount('/budgets')
    await findList()
    await user.click(screen.getAllByRole('button', { name: 'Novo orçamento' })[0]!)
    const dialog = await screen.findByRole('dialog', { name: 'Novo orçamento' })
    const select = within(dialog).getByLabelText('Categoria')
    await waitFor(() => expect(optionTexts(select)).toContain('Transporte'))
    // Mercado and Lazer already have a budget, Antiga is archived
    expect(optionTexts(select)).toEqual(['Selecione', 'Transporte'])

    await user.selectOptions(select, 'c3')
    await user.type(within(dialog).getByLabelText('Limite mensal'), '300')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    await waitFor(() =>
      expect(s.calls.post).toEqual({ categoryId: 'c3', monthlyLimitCents: 30000 }),
    )
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('budgets_createMapsBudgetExistsToCategoryField', async () => {
    const s = setup([http.post(`${api}/budgets`, () => problem(409, 'BUDGET_EXISTS'))])
    const { user } = await s.mount('/budgets')
    await findList()
    await user.click(screen.getAllByRole('button', { name: 'Novo orçamento' })[0]!)
    const dialog = await screen.findByRole('dialog', { name: 'Novo orçamento' })
    const select = within(dialog).getByLabelText('Categoria')
    await waitFor(() => expect(optionTexts(select)).toContain('Transporte'))
    await user.selectOptions(select, 'c3')
    await user.type(within(dialog).getByLabelText('Limite mensal'), '10')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(select.getAttribute('aria-invalid')).toBe('true'))
    expect(dialog.textContent).toContain('Já existe um orçamento para esta categoria.')
  })

  it('budgets_editChangesOnlyTheLimit', async () => {
    const s = setup()
    const { user } = await s.mount('/budgets')
    await findList()
    await user.click(screen.getByRole('button', { name: 'Editar orçamento de Mercado' }))
    const dialog = await screen.findByRole('dialog', { name: 'Editar orçamento' })
    expect(within(dialog).queryByLabelText('Categoria')).toBeNull()
    const limit = within(dialog).getByLabelText('Limite mensal')
    await user.clear(limit)
    await user.type(limit, '250')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(s.calls.put).toEqual({ monthlyLimitCents: 25000 }))
  })

  it('budgets_deleteAsksConfirmation', async () => {
    const s = setup()
    const { user } = await s.mount('/budgets')
    await findList()
    await user.click(screen.getByRole('button', { name: 'Excluir orçamento de Lazer' }))
    const dialog = await screen.findByRole('dialog', { name: 'Excluir orçamento?' })
    expect(s.calls.deleted).toBeUndefined()
    await user.click(within(dialog).getByRole('button', { name: 'Excluir' }))
    await waitFor(() => expect(s.calls.deleted).toBe('b2'))
  })
})
