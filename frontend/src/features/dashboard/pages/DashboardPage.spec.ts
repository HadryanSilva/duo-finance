import { screen, waitFor, within } from '@testing-library/dom'
import { http, HttpResponse, type RequestHandler } from 'msw'
import { describe, expect, it } from 'vitest'
import type { DashboardResponse, TransactionResponse } from '@/shared/api/types'
import { addMonths, currentMonth } from '@/shared/lib/dates'
import { renderRoute } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const norm = (s: string | null) => (s ?? '').replace(/\s/g, ' ')

const mercado = {
  id: 'c1',
  name: 'Mercado',
  archived: false,
  color: '#0ea5e9',
  icon: 'shopping-cart',
}

const full: DashboardResponse = {
  month: '2026-03',
  currency: 'EUR',
  balancesAsOf: '2026-03-31',
  totalBalanceCents: 350000,
  accounts: [
    { id: 'a1', name: 'Conta Corrente', type: 'CHECKING', balanceCents: 300000 },
    { id: 'a2', name: 'Poupança', type: 'SAVINGS', balanceCents: 50000 },
  ],
  incomeCents: 500000,
  expenseCents: 123456,
  spendingByCategory: [
    {
      categoryId: 'c1',
      name: 'Mercado',
      color: '#0ea5e9',
      icon: 'shopping-cart',
      totalCents: 45000,
    },
  ],
  budgets: [
    {
      id: 'b1',
      category: mercado,
      monthlyLimitCents: 60000,
      month: '2026-03',
      spentCents: 45000,
      percent: 75,
      status: 'NORMAL',
    },
  ],
  upcoming: [
    {
      ruleId: 'r1',
      date: '2026-04-05',
      kind: 'EXPENSE',
      amountCents: 150000,
      description: 'Aluguel',
      categoryId: null,
    },
  ],
}

const empty: DashboardResponse = {
  ...full,
  totalBalanceCents: 0,
  accounts: [],
  incomeCents: 0,
  expenseCents: 0,
  spendingByCategory: [],
  budgets: [],
  upcoming: [],
}

const tx = (id: string, description: string, date: string): TransactionResponse => ({
  id,
  kind: 'EXPENSE',
  amountCents: 2500,
  date,
  description,
  category: mercado,
  bankAccount: { id: 'a1', name: 'Conta Corrente', archived: false },
  destinationBankAccount: null,
  createdBy: { userId: 'u1', displayName: 'Ana Souza' },
  recurringRuleId: null,
  createdAt: '2026-03-30T10:00:00Z',
  updatedAt: '2026-03-30T10:00:00Z',
})

const page = (content: TransactionResponse[]) => ({
  content,
  page: 0,
  size: 5,
  totalElements: content.length,
  totalPages: 1,
})

function setup(dashboard: DashboardResponse, transactions: TransactionResponse[] = []) {
  const months: string[] = []
  const txQueries: URLSearchParams[] = []
  const handlers: RequestHandler[] = [
    http.get(`${api}/dashboard`, ({ request }) => {
      months.push(new URL(request.url).searchParams.get('month') ?? '')
      return HttpResponse.json(dashboard)
    }),
    http.get(`${api}/transactions`, ({ request }) => {
      txQueries.push(new URL(request.url).searchParams)
      return HttpResponse.json(page(transactions))
    }),
  ]
  return {
    months,
    txQueries,
    handlers,
    render: (route: string, extra: RequestHandler[] = []) =>
      renderRoute({ route, session: {}, handlers: [...extra, ...handlers] }),
  }
}

describe('DashboardPage', () => {
  it('dashboard_rendersAllCardsFromResponse', async () => {
    const s = setup(full, [tx('t1', 'Feira', '2026-03-30'), tx('t2', 'Padaria', '2026-03-29')])
    const { wrapper } = await s.render('/dashboard?month=2026-03')

    // balance: total, "as of" date and one row per account
    expect(await screen.findByText('Saldo total')).toBeTruthy()
    await waitFor(() => expect(norm(document.body.textContent)).toContain('€ 3.500,00'))
    expect(norm(document.body.textContent)).toContain('saldo em 31/03/2026')
    expect(screen.getByText('Conta Corrente')).toBeTruthy()
    expect(screen.getByText('Poupança')).toBeTruthy()

    // income and expense totals
    expect(norm(document.body.textContent)).toContain('€ 5.000,00')
    expect(norm(document.body.textContent)).toContain('€ 1.234,56')

    // budgets
    const bar = screen.getByRole('progressbar', { name: 'Mercado' })
    expect(bar.getAttribute('aria-valuenow')).toBe('75')

    // upcoming
    const upcoming = screen.getByRole('list', { name: 'Próximos lançamentos' })
    expect(norm(upcoming.textContent)).toContain('05/04/2026')
    expect(norm(upcoming.textContent)).toContain('Aluguel')

    // recent transactions + link to the full list
    const recent = await screen.findByRole('list', { name: 'Últimos lançamentos' })
    expect(within(recent).getAllByRole('listitem')).toHaveLength(2)
    expect(within(recent).getByText('Feira')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Ver todos' }).getAttribute('href')).toBe(
      '/transactions',
    )

    // both charts got an option (income x expense bars and the spending doughnut)
    await waitFor(() => expect(wrapper.findAllComponents({ name: 'VChart' })).toHaveLength(2))
  })

  it('recent_asksForFiveWithoutDateFilter', async () => {
    const s = setup(full, [tx('t1', 'Feira', '2026-03-30')])
    await s.render('/dashboard?month=2026-03')
    await waitFor(() => expect(s.txQueries).toHaveLength(1))
    const q = s.txQueries[0]!
    expect(q.get('size')).toBe('5')
    expect(q.has('from')).toBe(false)
    expect(q.has('to')).toBe(false)
  })

  it('dashboard_monthSelectorUpdatesUrlAndRefetches', async () => {
    const s = setup(full)
    const { router, user } = await s.render('/dashboard')
    await waitFor(() => expect(s.months).toContain(currentMonth()))

    await user.click(screen.getByRole('button', { name: 'Mês anterior' }))
    const prev = addMonths(currentMonth(), -1)
    await waitFor(() => expect(router.currentRoute.value.query.month).toBe(prev))
    await waitFor(() => expect(s.months).toContain(prev))

    await user.click(screen.getByRole('button', { name: 'Próximo mês' }))
    await waitFor(() => expect(router.currentRoute.value.query.month).toBe(currentMonth()))
  })

  it('dashboard_monthFromUrl', async () => {
    const s = setup(full)
    await s.render('/dashboard?month=2026-03')
    await waitFor(() => expect(s.months).toEqual(['2026-03']))
    expect(screen.getByText('Março de 2026')).toBeTruthy()
  })

  it('dashboard_emptyAccount_showsEmptyStatesWithCtas', async () => {
    const s = setup(empty, [])
    await s.render('/dashboard?month=2026-03')

    const href = (name: string) =>
      screen.findByRole('link', { name }).then((a) => a.getAttribute('href'))
    expect(await href('Criar primeira conta')).toBe('/bank-accounts?new=1')
    expect(await href('Definir orçamento')).toBe('/budgets')
    expect(await href('Criar recorrência')).toBe('/recurring')
    expect(await href('Registrar primeiro lançamento')).toBe('/transactions?new=1')
  })

  it('dashboard_loading_showsSkeletons', async () => {
    const s = setup(full)
    let release: () => void = () => {}
    const gate = new Promise<void>((resolve) => (release = resolve))
    await s.render('/dashboard?month=2026-03', [
      http.get(`${api}/dashboard`, async () => {
        await gate
        return HttpResponse.json(full)
      }),
    ])
    expect(document.querySelectorAll('[data-slot="skeleton"]').length).toBeGreaterThan(0)
    release()
    await screen.findByText('Saldo total')
  })

  it('dashboard_error_showsTranslatedMessage', async () => {
    const s = setup(full)
    await s.render('/dashboard?month=2026-03', [
      http.get(`${api}/dashboard`, () =>
        HttpResponse.json({ status: 500, code: 'INTERNAL_ERROR', detail: 'raw' }, { status: 500 }),
      ),
    ])
    const alert = await screen.findByText(
      'Algo deu errado do nosso lado. Tente novamente em instantes.',
    )
    expect(alert.textContent).not.toContain('raw')
    // the recent card has its own query and state; nothing else is left loading
    await screen.findByRole('link', { name: 'Ver todos' })
    await waitFor(() => expect(document.querySelectorAll('[data-slot="skeleton"]')).toHaveLength(0))
  })
})
