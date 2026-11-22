import { screen, waitFor, within } from '@testing-library/dom'
import { http, HttpResponse, type RequestHandler } from 'msw'
import { describe, expect, it } from 'vitest'
import { DAILY_ITEMS, MORE_ITEMS } from '@/app/components/nav'
import type { BudgetResponse, MonthReviewResponse } from '@/shared/api/types'
import { renderRoute } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const norm = (s: string | null) => (s ?? '').replace(/\s/g, ' ')

const cat = (id: string, name: string, cents: number, averageCents: number | null) => ({
  categoryId: id,
  name,
  color: '#0ea5e9',
  icon: 'shopping-cart',
  archived: false,
  cents,
  averageCents,
})

/** The approved sketch: October 2026 against the average of July to September. */
const october: MonthReviewResponse = {
  month: '2026-10',
  currency: 'EUR',
  firstMonth: '2026-03',
  lastClosedMonth: '2026-10',
  comparedMonths: 3,
  income: { cents: 320000, averageCents: 320000 },
  expense: { cents: 245000, averageCents: 214000 },
  left: { cents: 75000, averageCents: 106000 },
  categories: [
    cat('c-casa', 'Casa', 90000, 90000),
    cat('c-mercado', 'Mercado', 52000, 43000),
    cat('c-rest', 'Restaurantes', 21000, 12000),
    cat('c-transp', 'Transporte', 8000, 14000),
  ],
}

const budget = (
  categoryId: string,
  name: string,
  monthlyLimitCents: number,
  spentCents: number,
  status: BudgetResponse['status'],
): BudgetResponse => ({
  id: `b-${categoryId}`,
  category: { id: categoryId, name, color: '#0ea5e9', icon: 'shopping-cart', archived: false },
  monthlyLimitCents,
  month: '2026-10',
  spentCents,
  percent: Math.floor((spentCents * 100) / monthlyLimitCents),
  status,
})

function setup(
  respond: (month: string | null) => MonthReviewResponse,
  budgetsFor: (month: string | null) => BudgetResponse[] | 'error' = () => [],
) {
  const asked: (string | null)[] = []
  const budgetsAsked: (string | null)[] = []
  const created: unknown[] = []
  const handlers: RequestHandler[] = [
    http.get(`${api}/month-review`, ({ request }) => {
      const month = new URL(request.url).searchParams.get('month')
      asked.push(month)
      return HttpResponse.json(respond(month))
    }),
    http.get(`${api}/budgets`, ({ request }) => {
      const month = new URL(request.url).searchParams.get('month')
      budgetsAsked.push(month)
      const budgets = budgetsFor(month)
      return budgets === 'error'
        ? HttpResponse.json({ code: 'INTERNAL_ERROR', status: 500 }, { status: 500 })
        : HttpResponse.json(budgets)
    }),
    http.post(`${api}/budgets`, async ({ request }) => {
      created.push(await request.json())
      return HttpResponse.json(budget('c-rest', 'Restaurantes', 12000, 21000, 'EXCEEDED'), {
        status: 201,
      })
    }),
    http.get(`${api}/categories`, () => HttpResponse.json([])),
  ]
  return {
    asked,
    budgetsAsked,
    created,
    handlers,
    render: (route: string) => renderRoute({ route, session: {}, handlers }),
  }
}

/** Category names in table order (the row header also holds the phone layout's budget line). */
const rowNames = () =>
  screen.getAllByRole('rowheader').map((h) => norm(within(h).getByRole('link').textContent).trim())

/** The table row of a category, found by its row header. */
const rowOf = (name: string) =>
  screen.getAllByRole('row').find((r) =>
    norm(within(r).queryByRole('rowheader')?.textContent ?? null)
      .trim()
      .startsWith(name),
  )!

const limitIn = (panel: HTMLElement) =>
  norm((within(panel).getByLabelText('Limite mensal') as HTMLInputElement).value).replace(/^€ /, '')

const hasClass = (name: string) => (_: string, el: Element | null) =>
  el?.classList.contains(name) ?? false

describe('ReviewPage', () => {
  it('reviewPage_showsSummaryAndCategoriesOrdered', async () => {
    await setup(() => october).render('/review?month=2026-10')

    const table = await screen.findByRole('table')
    expect(rowNames()).toEqual(['Casa', 'Mercado', 'Restaurantes', 'Transporte'])
    expect(within(table).getByRole('columnheader', { name: 'média de 3 meses' })).toBeTruthy()
    const rows = within(table).getAllByRole('row')
    expect(norm(rows[1]!.textContent)).toContain('sem mudança')
    expect(norm(rows[2]!.textContent)).toContain('+€ 90,00')
    expect(norm(rows[2]!.textContent)).toContain('a mais')
  })

  it('reviewPage_summaryColoursByMeaning', async () => {
    await setup(() => october).render('/review?month=2026-10')
    const summary = await screen.findByLabelText('Resumo do mês comparado com a média')

    // Saiu €310 above the average is bad news, and so is Sobrou €310 below it.
    const worse = within(summary)
      .getAllByText(hasClass('text-expense'))
      .map((el) => norm(el.textContent))
    expect(worse).toEqual(
      expect.arrayContaining([
        expect.stringContaining('+€ 310,00'),
        expect.stringContaining('-€ 310,00'),
      ]),
    )
  })

  it('reviewPage_orderToggleSwitchesToChange', async () => {
    const { user } = await setup(() => october).render('/review?month=2026-10')
    await screen.findByRole('table')

    const group = screen.getByRole('group', { name: 'Ordenar por' })
    const size = within(group).getByRole('button', { name: 'Maior gasto' })
    const change = within(group).getByRole('button', { name: 'Maior mudança' })
    expect(size.getAttribute('aria-pressed')).toBe('true')
    await user.click(change)
    expect(rowNames()).toEqual(['Mercado', 'Restaurantes', 'Transporte', 'Casa'])
    expect(change.getAttribute('aria-pressed')).toBe('true')
    expect(size.getAttribute('aria-pressed')).toBe('false')
  })

  it('reviewPage_showsShareColumn', async () => {
    await setup(() => october).render('/review?month=2026-10')
    const table = await screen.findByRole('table')

    expect(within(table).getByRole('columnheader', { name: '% da saída' })).toBeTruthy()
    expect(norm(rowOf('Casa').textContent)).toContain('37%')
    expect(norm(rowOf('Transporte').textContent)).toContain('3%')
  })

  it('reviewPage_budgetColumnShowsStatus', async () => {
    await setup(
      () => october,
      () => [
        budget('c-mercado', 'Mercado', 45000, 52000, 'EXCEEDED'),
        budget('c-casa', 'Casa', 95000, 90000, 'WARNING'),
        budget('c-transp', 'Transporte', 10000, 8000, 'NORMAL'),
      ],
    ).render('/review?month=2026-10')
    await screen.findAllByText('estourou')

    expect(screen.getByRole('columnheader', { name: /Orçamento atual/ })).toBeTruthy()
    expect(norm(rowOf('Mercado').textContent)).toContain('€ 450,00')
    expect(norm(rowOf('Mercado').textContent)).toContain('estourou')
    expect(norm(rowOf('Casa').textContent)).toContain('€ 950,00')
    expect(norm(rowOf('Casa').textContent)).toContain('perto do limite')
    expect(norm(rowOf('Transporte').textContent)).toContain('dentro do orçamento')
    // On a phone the budget column is hidden and the budget shows under the category name.
    const mercadoHeader = within(rowOf('Mercado')).getByRole('rowheader')
    expect(norm(mercadoHeader.textContent)).toContain('€ 450,00')
    expect(within(mercadoHeader).getByText('estourou')).toBeTruthy()
    expect(norm(within(rowOf('Restaurantes')).getByRole('rowheader').textContent)).not.toContain(
      '—',
    )
    expect(norm(rowOf('Restaurantes').textContent)).toContain('—')
    expect(
      within(rowOf('Restaurantes')).getByRole('button', {
        name: 'Definir orçamento de Restaurantes',
      }),
    ).toBeTruthy()
    expect(
      within(rowOf('Mercado')).getByRole('button', { name: 'Ajustar orçamento de Mercado' }),
    ).toBeTruthy()
  })

  it('reviewPage_budgetsFollowMonth', async () => {
    const s = setup((m) => ({ ...october, month: m ?? '2026-10' }))
    const { user } = await s.render('/review?month=2026-10')
    await screen.findByRole('table')

    await user.click(screen.getByRole('button', { name: 'Mês anterior' }))
    await waitFor(() => expect(s.budgetsAsked).toEqual(['2026-10', '2026-09']))
  })

  it('reviewPage_budgetsErrorKeepsReview', async () => {
    await setup(
      () => october,
      () => 'error',
    ).render('/review?month=2026-10')

    expect(await screen.findByText('Não foi possível carregar os orçamentos.')).toBeTruthy()
    expect(rowNames()).toEqual(['Casa', 'Mercado', 'Restaurantes', 'Transporte'])
  })

  it('reviewPage_definirCreatesBudgetAndRefreshes', async () => {
    let saved = false
    const s = setup(
      () => october,
      () => (saved ? [budget('c-rest', 'Restaurantes', 12000, 21000, 'EXCEEDED')] : []),
    )
    const { user } = await s.render('/review?month=2026-10')
    await screen.findByRole('table')

    await user.click(
      await screen.findByRole('button', { name: 'Definir orçamento de Restaurantes' }),
    )
    const panel = await screen.findByRole('dialog')
    expect(within(panel).getByText('Restaurantes')).toBeTruthy()
    expect(limitIn(panel)).toBe('120,00')
    expect(within(panel).getByText('média dos últimos 3 meses')).toBeTruthy()

    saved = true
    await user.click(within(panel).getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(s.created).toEqual([{ categoryId: 'c-rest', monthlyLimitCents: 12000 }])
    await waitFor(() => expect(norm(rowOf('Restaurantes').textContent)).toContain('€ 120,00'))
    expect(
      within(rowOf('Restaurantes')).getByRole('button', {
        name: 'Ajustar orçamento de Restaurantes',
      }),
    ).toBeTruthy()
  })

  it('reviewPage_ajustarStartsOnCurrentLimit', async () => {
    const { user } = await setup(
      () => october,
      () => [budget('c-mercado', 'Mercado', 45000, 52000, 'EXCEEDED')],
    ).render('/review?month=2026-10')
    await screen.findAllByText('estourou')

    await user.click(screen.getByRole('button', { name: 'Ajustar orçamento de Mercado' }))
    const panel = await screen.findByRole('dialog')
    expect(limitIn(panel)).toBe('450,00')
    expect(within(panel).getByRole('button', { name: /Usar sugestão: €\s430,00/ })).toBeTruthy()
  })

  it('reviewPage_archivedCategoryHasNoAction', async () => {
    const archived: MonthReviewResponse = {
      ...october,
      categories: october.categories.map((c) =>
        c.categoryId === 'c-transp' ? { ...c, archived: true } : c,
      ),
    }
    await setup(
      () => archived,
      () => [budget('c-transp', 'Transporte', 10000, 8000, 'NORMAL')],
    ).render('/review?month=2026-10')
    await screen.findAllByText('dentro do orçamento')

    expect(norm(rowOf('Transporte').textContent)).toContain('€ 100,00')
    expect(within(rowOf('Transporte')).queryByRole('button')).toBeNull()
  })

  it('reviewPage_firstMonthSuggestsMonthAmount', async () => {
    const first: MonthReviewResponse = {
      ...october,
      firstMonth: '2026-10',
      comparedMonths: 0,
      income: { cents: 320000, averageCents: null },
      expense: { cents: 245000, averageCents: null },
      left: { cents: 75000, averageCents: null },
      categories: october.categories.map((c) => ({ ...c, averageCents: null })),
    }
    const { user } = await setup(() => first).render('/review?month=2026-10')
    await screen.findByRole('table')

    expect(screen.queryByRole('group', { name: 'Ordenar por' })).toBeNull()
    await user.click(
      await screen.findByRole('button', { name: 'Definir orçamento de Restaurantes' }),
    )
    const panel = await screen.findByRole('dialog')
    expect(limitIn(panel)).toBe('210,00')
    expect(within(panel).getByText('gasto deste mês')).toBeTruthy()
  })

  it('reviewPage_categoryLinksToTransactions', async () => {
    await setup(() => october).render('/review?month=2026-10')

    const link = await screen.findByRole('link', { name: /Mercado/ })
    const href = link.getAttribute('href') ?? ''
    expect(href).toContain('/transactions?')
    expect(href).toContain('categoryId=c-mercado')
    expect(href).toContain('from=2026-10-01')
    expect(href).toContain('to=2026-10-31')
  })

  it('reviewPage_clampsMonthFromUrl', async () => {
    const s = setup((m) => ({ ...october, month: m ?? '2026-10' }))
    const { router } = await s.render('/review?month=2030-01')

    await waitFor(() => expect(router.currentRoute.value.query.month).toBe('2026-10'))
    await screen.findByRole('table')
    // the first request asks for the server's default month, never for the out-of-range one
    expect(s.asked).toContain(null)
    expect(s.asked).not.toContain('2030-01')
  })

  it('reviewPage_monthBeforeFirst_isClampedToFirst', async () => {
    const s = setup((m) => ({ ...october, month: m ?? '2026-10' }))
    const { router } = await s.render('/review?month=2025-01')

    await waitFor(() => expect(router.currentRoute.value.query.month).toBe('2026-03'))
    await waitFor(() => expect(s.asked).toEqual([null, '2026-03']))
  })

  it('reviewPage_atLastClosedMonth_nextDisabled', async () => {
    const s = setup((m) => ({ ...october, month: m ?? '2026-10' }))
    const { router, user } = await s.render('/review')

    await screen.findByRole('table')
    expect(screen.getByRole<HTMLButtonElement>('button', { name: 'Próximo mês' }).disabled).toBe(
      true,
    )
    await user.click(screen.getByRole('button', { name: 'Mês anterior' }))
    await waitFor(() => expect(router.currentRoute.value.query.month).toBe('2026-09'))
    await waitFor(() => expect(s.asked).toContain('2026-09'))
  })

  it('reviewPage_firstMonth_hidesComparison', async () => {
    const first: MonthReviewResponse = {
      ...october,
      comparedMonths: 0,
      income: { cents: 320000, averageCents: null },
      expense: { cents: 245000, averageCents: null },
      left: { cents: 75000, averageCents: null },
      categories: october.categories.map((c) => ({ ...c, averageCents: null })),
    }
    await setup(() => first).render('/review?month=2026-10')

    const table = await screen.findByRole('table')
    expect(within(table).queryByRole('columnheader', { name: /média/ })).toBeNull()
    expect(within(table).queryByRole('columnheader', { name: 'Diferença' })).toBeNull()
    expect(screen.queryByText(/média de/)).toBeNull()
    expect(rowNames()).toEqual(['Casa', 'Mercado', 'Restaurantes', 'Transporte'])
  })

  it('reviewPage_noTransactions_showsEmpty', async () => {
    const empty: MonthReviewResponse = {
      ...october,
      firstMonth: null,
      comparedMonths: 0,
      income: { cents: 0, averageCents: null },
      expense: { cents: 0, averageCents: null },
      left: { cents: 0, averageCents: null },
      categories: [],
    }
    await setup(() => empty).render('/review')

    expect(await screen.findByText('Ainda não há lançamentos para revisar.')).toBeTruthy()
    expect(screen.queryByRole('group', { name: 'Mês' })).toBeNull()
    expect(screen.getByRole('link', { name: 'Importar extrato' }).getAttribute('href')).toBe(
      '/transactions/import',
    )
  })

  /** A couple in its first month has transactions, but no month is over yet. */
  it('reviewPage_onlyRunningMonthHasData_showsEmptyWithoutError', async () => {
    const s = setup((m) => ({
      ...october,
      month: m ?? '2026-10',
      firstMonth: '2026-11',
      lastClosedMonth: '2026-10',
      comparedMonths: 0,
      categories: [],
    }))
    await s.render('/review')

    expect(await screen.findByText('Ainda não há lançamentos para revisar.')).toBeTruthy()
    await new Promise((r) => setTimeout(r, 50))
    expect(screen.queryByRole('alert')).toBeNull()
    expect(s.asked).not.toContain('2026-11')
    expect(screen.queryByRole('group', { name: 'Mês' })).toBeNull()
  })

  it('reviewPage_changingMonth_keepsSelectorAndFocus', async () => {
    let release: () => void = () => {}
    const s = setup((m) => ({ ...october, month: m ?? '2026-10' }))
    const slow = http.get(`${api}/month-review`, async ({ request }) => {
      if (new URL(request.url).searchParams.get('month') !== '2026-09') return undefined
      await new Promise<void>((r) => (release = r))
      return HttpResponse.json({ ...october, month: '2026-09' })
    })
    const { user } = await renderRoute({
      route: '/review',
      session: {},
      handlers: [slow, ...s.handlers],
    })
    await screen.findByRole('table')

    const previous = screen.getByRole('button', { name: 'Mês anterior' })
    await user.click(previous)
    // while September loads, the selector stays and keeps the focus
    await waitFor(() => expect(screen.getByText('Setembro de 2026')).toBeTruthy())
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Mês anterior' }))
    release()
    await waitFor(() => expect(screen.getByRole('table')).toBeTruthy())
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Mês anterior' }))
  })

  it('reviewPage_failedMonth_keepsSelector', async () => {
    const s = setup((m) => ({ ...october, month: m ?? '2026-10' }))
    const failing = http.get(`${api}/month-review`, ({ request }) =>
      new URL(request.url).searchParams.get('month') === '2026-09'
        ? HttpResponse.json({ code: 'INTERNAL_ERROR', status: 500 }, { status: 500 })
        : undefined,
    )
    const { user } = await renderRoute({
      route: '/review',
      session: {},
      handlers: [failing, ...s.handlers],
    })
    await screen.findByRole('table')

    await user.click(screen.getByRole('button', { name: 'Mês anterior' }))
    expect(await screen.findByRole('alert')).toBeTruthy()
    await user.click(screen.getByRole('button', { name: 'Próximo mês' }))
    expect(await screen.findByRole('table')).toBeTruthy()
  })
})

describe('navigation', () => {
  it('nav_includesReviewAfterDashboard', () => {
    expect(DAILY_ITEMS[0]!.to).toBe('/dashboard')
    expect(DAILY_ITEMS[1]!.to).toBe('/review')
    expect(MORE_ITEMS.map((i) => i.to)).toContain('/review')
  })
})
