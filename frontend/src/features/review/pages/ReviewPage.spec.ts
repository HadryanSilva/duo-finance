import { screen, waitFor, within } from '@testing-library/dom'
import { http, HttpResponse, type RequestHandler } from 'msw'
import { describe, expect, it } from 'vitest'
import { DAILY_ITEMS, MORE_ITEMS } from '@/app/components/nav'
import type { MonthReviewResponse } from '@/shared/api/types'
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

function setup(respond: (month: string | null) => MonthReviewResponse) {
  const asked: (string | null)[] = []
  const handlers: RequestHandler[] = [
    http.get(`${api}/month-review`, ({ request }) => {
      const month = new URL(request.url).searchParams.get('month')
      asked.push(month)
      return HttpResponse.json(respond(month))
    }),
  ]
  return {
    asked,
    render: (route: string) => renderRoute({ route, session: {}, handlers }),
  }
}

const rowNames = () => screen.getAllByRole('rowheader').map((h) => norm(h.textContent).trim())

const hasClass = (name: string) => (_: string, el: Element | null) =>
  el?.classList.contains(name) ?? false

describe('ReviewPage', () => {
  it('reviewPage_showsSummaryAndCategoriesOrdered', async () => {
    await setup(() => october).render('/review?month=2026-10')

    const table = await screen.findByRole('table')
    expect(rowNames()).toEqual(['Mercado', 'Restaurantes', 'Transporte', 'Casa'])
    expect(within(table).getByRole('columnheader', { name: 'média de 3 meses' })).toBeTruthy()
    const rows = within(table).getAllByRole('row')
    expect(norm(rows[1]!.textContent)).toContain('+€ 90,00')
    expect(norm(rows[1]!.textContent)).toContain('a mais')
    expect(norm(rows[4]!.textContent)).toContain('—')
    expect(norm(rows[4]!.textContent)).toContain('sem mudança')
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
})

describe('navigation', () => {
  it('nav_includesReviewAfterDashboard', () => {
    expect(DAILY_ITEMS[0]!.to).toBe('/dashboard')
    expect(DAILY_ITEMS[1]!.to).toBe('/review')
    expect(MORE_ITEMS.map((i) => i.to)).toContain('/review')
  })
})
