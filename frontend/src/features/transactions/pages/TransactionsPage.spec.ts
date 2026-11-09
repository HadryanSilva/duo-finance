import { fireEvent, screen, waitFor, within } from '@testing-library/dom'
import { http, HttpResponse, type RequestHandler } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '@/app/App.vue'
import { todayIso } from '@/shared/lib/dates'
import { render, renderRoute, testUser } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, detail: 'raw' }, { status })
const norm = (s: string | null | undefined) => (s ?? '').replace(/\s/g, ' ')

const ANA = testUser.id
const BIA = '00000000-0000-4000-8000-000000000002'
const account = {
  id: testUser.accountId,
  name: 'Ana e Bia',
  currency: 'EUR',
  members: [
    { userId: ANA, displayName: 'Ana Souza', email: 'a@x.com', joinedAt: '2026-01-01T00:00:00Z' },
    { userId: BIA, displayName: 'Bia Lima', email: 'b@x.com', joinedAt: '2026-01-02T00:00:00Z' },
  ],
  pendingInvitation: null,
}

const cat = { color: '#0000aa', systemDefault: false, archived: false }
const mercado = { ...cat, id: 'c1', name: 'Mercado', kind: 'EXPENSE', icon: 'shopping-cart' }
const salario = { ...cat, id: 'c2', name: 'Salário', kind: 'INCOME', icon: 'banknote' }
const antiga = { ...cat, id: 'c3', name: 'Antiga', kind: 'EXPENSE', icon: 'gift', archived: true }
const categories = [mercado, salario, antiga]

const bank = { type: 'CHECKING', initialBalanceCents: 0, balanceCents: 0, holderUserId: null }
const nubank = { ...bank, id: 'b1', name: 'Nubank', archived: false, createdAt: '', updatedAt: '' }
const cofre = { ...nubank, id: 'b2', name: 'Cofre' }
const velha = { ...nubank, id: 'b3', name: 'Velha', archived: true }
const banks = [nubank, cofre, velha]

const ref = (c: { id: string; name: string; archived: boolean }) => ({
  id: c.id,
  name: c.name,
  archived: c.archived,
})
const catRef = (c: typeof mercado) => ({ ...ref(c), color: c.color, icon: c.icon })
const tx = {
  description: null,
  category: null,
  bankAccount: null,
  destinationBankAccount: null,
  recurringRuleId: null,
  createdAt: '2026-11-09T10:00:00Z',
  updatedAt: '2026-11-09T10:00:00Z',
}
const t1 = {
  ...tx,
  id: 't1',
  kind: 'INCOME',
  amountCents: 100000,
  date: '2026-11-09',
  category: catRef(salario),
  bankAccount: ref(nubank),
  createdBy: { userId: ANA, displayName: 'Ana Souza' },
}
const t2 = {
  ...tx,
  id: 't2',
  kind: 'EXPENSE',
  amountCents: 2550,
  date: '2026-11-09',
  description: 'Feira',
  category: catRef(mercado),
  bankAccount: ref(nubank),
  createdBy: null,
}
const t3 = {
  ...tx,
  id: 't3',
  kind: 'TRANSFER',
  amountCents: 5000,
  date: '2026-11-09',
  bankAccount: ref(nubank),
  destinationBankAccount: ref(cofre),
  createdBy: { userId: BIA, displayName: 'Bia Lima' },
}
const t4 = {
  ...tx,
  id: 't4',
  kind: 'EXPENSE',
  amountCents: 1000,
  date: '2026-11-08',
  category: catRef(antiga),
  bankAccount: ref(velha),
  createdBy: { userId: ANA, displayName: 'Ana Souza' },
}
const all = [t1, t2, t3, t4]

const page = (content: unknown[], p = 0, totalPages = 1) => ({
  content,
  page: p,
  size: 50,
  totalElements: content.length,
  totalPages,
})

function setup(extra: RequestHandler[] = []) {
  const requests: URLSearchParams[] = []
  const calls: Record<string, unknown> = {}
  const handlers = [
    ...extra,
    http.get(`${api}/account`, () => HttpResponse.json(account)),
    http.get(`${api}/categories`, ({ request }) => {
      const p = new URL(request.url).searchParams
      const kind = p.get('kind')
      const archived = p.get('includeArchived') === 'true'
      return HttpResponse.json(
        categories.filter((c) => (!kind || c.kind === kind) && (archived || !c.archived)),
      )
    }),
    http.get(`${api}/bank-accounts`, ({ request }) => {
      const archived = new URL(request.url).searchParams.get('includeArchived') === 'true'
      return HttpResponse.json(banks.filter((b) => archived || !b.archived))
    }),
    http.get(`${api}/transactions/:id`, ({ params }) => {
      const found = all.find((t) => t.id === params.id)
      return found ? HttpResponse.json(found) : problem(404, 'NOT_FOUND')
    }),
    http.get(`${api}/transactions`, ({ request }) => {
      requests.push(new URL(request.url).searchParams)
      return HttpResponse.json(page(all))
    }),
    http.post(`${api}/transactions`, async ({ request }) => {
      calls.post = await request.json()
      return HttpResponse.json(t1, { status: 201 })
    }),
  ]
  return {
    requests,
    calls,
    render: (route: string) => renderRoute({ route, session: {}, handlers }),
    // renderRoute waits for the page heading, which an open side panel hides from the a11y tree
    mount: (route: string) => render(App, { route, session: {}, handlers }),
  }
}

const monthBounds = () => {
  const [y = 0, m = 1] = todayIso().split('-').map(Number)
  const last = new Date(y, m, 0).getDate()
  const mm = String(m).padStart(2, '0')
  return { from: `${y}-${mm}-01`, to: `${y}-${mm}-${last}` }
}

const findList = () => screen.findByRole('list', { name: 'Lançamentos por dia' })

const optionTexts = (select: HTMLElement) =>
  Array.from((select as HTMLSelectElement).options).map((o) => o.text.trim())

afterEach(() => {
  vi.useRealTimers()
})

describe('TransactionsPage', () => {
  it('list_groupsByDayWithDailyNet', async () => {
    await setup().render('/transactions')
    const day9 = await screen.findByRole('list', { name: '9 de novembro de 2026' })
    expect(within(day9).getAllByRole('listitem')).toHaveLength(3)
    // income − expense, the transfer left out
    expect(norm(screen.getByTestId('day-net-2026-11-09').textContent)).toBe('+€ 974,50')

    const day8 = screen.getByRole('list', { name: '8 de novembro de 2026' })
    expect(within(day8).getAllByRole('listitem')).toHaveLength(1)
    expect(norm(screen.getByTestId('day-net-2026-11-08').textContent)).toBe('-€ 10,00')
  })

  it('row_showsFormerMemberAndArchivedBadges', async () => {
    await setup().render('/transactions')
    const day9 = await screen.findByRole('list', { name: '9 de novembro de 2026' })
    const [income, expense, transfer] = within(day9).getAllByRole('listitem')

    // no description: the category name stands in
    expect(within(income!).getByText('Salário')).toBeTruthy()
    const ana = within(income!).getByRole('img', { name: 'Ana Souza' })
    expect(ana.textContent?.trim()).toBe('AS')
    expect(norm(income!.textContent)).toContain('+€ 1.000,00')

    expect(within(expense!).getByText('Feira')).toBeTruthy()
    expect(within(expense!).getByRole('img', { name: 'Ex-membro' })).toBeTruthy()

    expect(norm(transfer!.textContent)).toContain('Nubank → Cofre')

    const day8 = screen.getByRole('list', { name: '8 de novembro de 2026' })
    const archived = within(day8).getByRole('listitem')
    expect(within(archived).getByText('Antiga')).toBeTruthy()
    // archived category and archived bank account both keep a badge
    expect(within(archived).getAllByText('Arquivada')).toHaveLength(2)
    expect(within(expense!).queryByText('Arquivada')).toBeNull()
  })

  it('form_hidesArchivedOptionsForNew', async () => {
    await setup().mount('/transactions?new=1')
    const dialog = await screen.findByRole('dialog', { name: 'Novo lançamento' })
    const category = within(dialog).getByLabelText('Categoria')
    await waitFor(() => expect(optionTexts(category)).toContain('Mercado'))
    expect(optionTexts(category).join()).not.toContain('Antiga')
    const account = within(dialog).getByLabelText('Conta')
    await waitFor(() => expect(optionTexts(account)).toContain('Nubank'))
    expect(optionTexts(account)).toContain('Cofre')
    expect(optionTexts(account).join()).not.toContain('Velha')
  })

  it('form_editShowsCurrentArchivedOption', async () => {
    await setup().mount('/transactions?edit=t4')
    const dialog = await screen.findByRole('dialog', { name: 'Editar lançamento' })
    const category = (await within(dialog).findByLabelText('Categoria')) as HTMLSelectElement
    await waitFor(() => expect(optionTexts(category)).toContain('Antiga (arquivada)'))
    expect(category.value).toBe('c3')
    const account = within(dialog).getByLabelText('Conta') as HTMLSelectElement
    expect(optionTexts(account)).toContain('Velha (arquivada)')
    expect(account.value).toBe('b3')
  })

  it('form_transferRequiresDistinctAccounts', async () => {
    const s = setup()
    const { user } = await s.mount('/transactions?new=1')
    const dialog = await screen.findByRole('dialog', { name: 'Novo lançamento' })
    // switching the kind clears a category of the other kind
    const category = within(dialog).getByLabelText('Categoria') as HTMLSelectElement
    await waitFor(() => expect(optionTexts(category)).toContain('Mercado'))
    await user.selectOptions(category, 'c1')
    await user.click(within(dialog).getByRole('radio', { name: 'Receita' }))
    await waitFor(() => expect(optionTexts(category)).toContain('Salário'))
    expect(category.value).toBe('')
    await user.click(within(dialog).getByRole('radio', { name: 'Transferência' }))
    expect(within(dialog).queryByLabelText('Categoria')).toBeNull()
    await user.type(within(dialog).getByLabelText('Valor'), '10')
    const origin = within(dialog).getByLabelText('Conta de origem')
    const destination = within(dialog).getByLabelText('Conta de destino')
    await waitFor(() => expect(optionTexts(origin)).toContain('Nubank'))
    await user.selectOptions(origin, 'b1')
    await user.selectOptions(destination, 'b1')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(destination.getAttribute('aria-invalid')).toBe('true'))
    expect(dialog.textContent).toContain('A conta de destino precisa ser diferente da origem.')
    expect(s.calls.post).toBeUndefined()

    await user.selectOptions(destination, 'b2')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    await waitFor(() =>
      expect(s.calls.post).toEqual({
        kind: 'TRANSFER',
        amountCents: 1000,
        date: todayIso(),
        bankAccountId: 'b1',
        destinationBankAccountId: 'b2',
      }),
    )
    // saved: the panel closes once the mutation and its invalidations settled
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('filters_syncWithUrlAndDebounceSearch', async () => {
    const s = setup()
    const { router, user } = await s.render('/transactions?kind=EXPENSE&q=  mer  ')
    await screen.findByRole('list', { name: '9 de novembro de 2026' })
    const { from, to } = monthBounds()
    const first = s.requests[0]!
    expect(first.get('kind')).toBe('EXPENSE')
    expect(first.get('q')).toBe('mer')
    expect(first.get('from')).toBe(from)
    expect(first.get('to')).toBe(to)
    expect(first.get('size')).toBe('50')

    const filters = screen.getByRole('search', { name: 'Filtros' })
    const search = within(filters).getByLabelText('Buscar') as HTMLInputElement
    expect(search.value).toBe('mer')
    expect(search.maxLength).toBe(100)

    await user.selectOptions(within(filters).getByLabelText('Tipo'), 'INCOME')
    await waitFor(() => expect(router.currentRoute.value.query.kind).toBe('INCOME'))
    await waitFor(() => expect(s.requests.at(-1)?.get('kind')).toBe('INCOME'))

    vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'] })
    const before = s.requests.length
    fireEvent.input(search, { target: { value: '  feira ' } })
    await vi.advanceTimersByTimeAsync(299)
    expect(router.currentRoute.value.query.q).toBe('  mer  ') // untouched until the debounce fires
    expect(s.requests.slice(before).some((p) => p.get('q') === 'feira')).toBe(false)
    await vi.advanceTimersByTimeAsync(1)
    expect(router.currentRoute.value.query.q).toBe('feira')
    vi.useRealTimers()
    await waitFor(() => expect(s.requests.at(-1)?.get('q')).toBe('feira'))
    expect(s.requests.at(-1)?.get('kind')).toBe('INCOME')
  })

  it('filters_foreignCategory404_removesFilter', async () => {
    const s = setup([
      http.get(`${api}/transactions`, ({ request }) =>
        new URL(request.url).searchParams.get('categoryId') === 'foreign'
          ? problem(404, 'NOT_FOUND')
          : HttpResponse.json(page([t1])),
      ),
    ])
    const { router } = await s.render('/transactions?categoryId=foreign&kind=INCOME')
    await waitFor(() => expect(router.currentRoute.value.query.categoryId).toBeUndefined())
    expect(router.currentRoute.value.query.kind).toBe('INCOME')
    expect((await screen.findByRole('alert')).textContent).toContain(
      'O filtro de categoria ou conta não existe mais e foi removido.',
    )
    expect(within(await findList()).getByText('Salário')).toBeTruthy()
  })

  it('loadMore_appendsNextPage', async () => {
    const s = setup([
      http.get(`${api}/transactions`, ({ request }) =>
        new URL(request.url).searchParams.get('page') === '1'
          ? HttpResponse.json(page([t4], 1, 2))
          : HttpResponse.json(page([t1], 0, 2)),
      ),
    ])
    const { user } = await s.render('/transactions')
    const list = await findList()
    await within(list).findByText('Salário')
    expect(within(list).queryByText('Antiga')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Carregar mais' }))
    await within(list).findByText('Antiga')
    expect(within(list).getByText('Salário')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Carregar mais' })).toBeNull()
  })

  it('newQueryParam_opensEmptyForm', async () => {
    const { router, user } = await setup().mount('/transactions?new=1')
    const dialog = await screen.findByRole('dialog', { name: 'Novo lançamento' })
    expect(
      (within(dialog).getByRole('radio', { name: 'Despesa' }) as HTMLInputElement).checked,
    ).toBe(true)
    expect((within(dialog).getByLabelText('Valor') as HTMLInputElement).value).toBe('')
    expect((within(dialog).getByLabelText('Data') as HTMLInputElement).value).toBe(todayIso())
    expect((within(dialog).getByLabelText('Descrição') as HTMLInputElement).value).toBe('')
    expect(within(dialog).queryByRole('button', { name: 'Excluir' })).toBeNull()

    await user.keyboard('{Escape}')
    await waitFor(() => expect(router.currentRoute.value.query.new).toBeUndefined())
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
  })

  it('recurringDateTaken_showsDateFieldError', async () => {
    const s = setup([
      http.put(`${api}/transactions/t2`, () => problem(409, 'RECURRING_DATE_TAKEN')),
    ])
    const { user } = await s.mount('/transactions?edit=t2')
    const dialog = await screen.findByRole('dialog', { name: 'Editar lançamento' })
    const description = (await within(dialog).findByLabelText('Descrição')) as HTMLInputElement
    expect(description.value).toBe('Feira')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    const date = within(dialog).getByLabelText('Data')
    await waitFor(() => expect(date.getAttribute('aria-invalid')).toBe('true'))
    expect(dialog.textContent).toContain('Já existe um lançamento recorrente nesta data.')
  })
})
