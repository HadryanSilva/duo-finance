import { screen, waitFor, within } from '@testing-library/dom'
import { http, HttpResponse, type RequestHandler } from 'msw'
import { describe, expect, it } from 'vitest'
import App from '@/app/App.vue'
import { todayIso } from '@/shared/lib/dates'
import { render, renderRoute } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, detail: 'raw' }, { status })
const norm = (s: string | null | undefined) => (s ?? '').replace(/\s/g, ' ')

const cat = { color: '#0000aa', systemDefault: false, archived: false }
const moradia = { ...cat, id: 'c1', name: 'Moradia', kind: 'EXPENSE', icon: 'home' }
const salario = { ...cat, id: 'c2', name: 'Salário', kind: 'INCOME', icon: 'banknote' }
const categories = [moradia, salario]

const bank = { type: 'CHECKING', initialBalanceCents: 0, balanceCents: 0, holderUserId: null }
const nubank = { ...bank, id: 'b1', name: 'Nubank', archived: false, createdAt: '', updatedAt: '' }
const banks = [nubank]

const catRef = (c: typeof moradia) => ({
  id: c.id,
  name: c.name,
  archived: c.archived,
  color: c.color,
  icon: c.icon,
})
const base = {
  description: null,
  category: null,
  bankAccount: null,
  destinationBankAccount: null,
  endDate: null,
  finished: false,
  active: true,
  createdBy: null,
}
const aluguel = {
  ...base,
  id: 'r1',
  kind: 'EXPENSE',
  amountCents: 120000,
  description: 'Aluguel',
  category: catRef(moradia),
  frequency: 'MONTHLY',
  startDate: '2026-01-05',
  nextRunDate: '2026-12-05',
}
const pausada = {
  ...base,
  id: 'r2',
  kind: 'INCOME',
  amountCents: 50000,
  description: 'Freela',
  category: catRef(salario),
  frequency: 'WEEKLY',
  startDate: '2026-02-01',
  nextRunDate: '2026-12-07',
  active: false,
}
const encerrada = {
  ...base,
  id: 'r3',
  kind: 'EXPENSE',
  amountCents: 3000,
  description: 'Curso',
  category: catRef(moradia),
  frequency: 'YEARLY',
  startDate: '2025-03-10',
  endDate: '2026-03-10',
  nextRunDate: '2027-03-10',
  finished: true,
  active: false,
}

function setup(extra: RequestHandler[] = []) {
  const calls: Record<string, unknown> = {}
  const handlers = [
    ...extra,
    http.get(`${api}/categories`, ({ request }) => {
      const kind = new URL(request.url).searchParams.get('kind')
      return HttpResponse.json(categories.filter((c) => !kind || c.kind === kind))
    }),
    http.get(`${api}/bank-accounts`, () => HttpResponse.json(banks)),
    http.get(`${api}/recurring-rules`, () => HttpResponse.json([aluguel, pausada, encerrada])),
    http.post(`${api}/recurring-rules/:id/activate`, ({ params }) => {
      calls.activated = params.id
      return HttpResponse.json({ ...pausada, active: true })
    }),
    http.post(`${api}/recurring-rules/:id/deactivate`, ({ params }) => {
      calls.deactivated = params.id
      return HttpResponse.json({ ...aluguel, active: false })
    }),
    http.post(`${api}/recurring-rules`, async ({ request }) => {
      calls.post = await request.json()
      return HttpResponse.json(aluguel, { status: 201 })
    }),
    http.put(`${api}/recurring-rules/:id`, async ({ request }) => {
      calls.put = await request.json()
      return HttpResponse.json(aluguel)
    }),
    http.delete(`${api}/recurring-rules/:id`, ({ params }) => {
      calls.deleted = params.id
      return new HttpResponse(null, { status: 204 })
    }),
  ]
  return {
    calls,
    render: (route: string) => renderRoute({ route, session: {}, handlers }),
    mount: (route: string) => render(App, { route, session: {}, handlers }),
  }
}

const section = async (name: string) => {
  const heading = await screen.findByRole('heading', { name, level: 2 })
  return heading.closest('section') as HTMLElement
}

describe('RecurringPage', () => {
  it('recurring_splitsActiveAndFinished', async () => {
    await setup().render('/recurring')
    const active = await section('Ativas')
    const inactive = await section('Inativas ou encerradas')
    const activeRows = within(active).getAllByRole('listitem')
    expect(activeRows).toHaveLength(1)
    const text = norm(activeRows[0]!.textContent)
    expect(text).toContain('Aluguel')
    expect(text).toContain('Mensal')
    expect(text).toContain('Próximo: 05/12/2026')
    expect(text).toContain('€ 1.200,00')

    const inactiveRows = within(inactive).getAllByRole('listitem')
    expect(inactiveRows).toHaveLength(2)
    expect(norm(inactiveRows[0]!.textContent)).toContain('Semanal')
    expect(norm(inactiveRows[1]!.textContent)).toContain('Encerrada')
    // a finished rule cannot be toggled
    expect(within(inactiveRows[1]!).queryByRole('button', { name: /^Ativar/ })).toBeNull()
  })

  it('recurring_activateAndDeactivate', async () => {
    const s = setup()
    const { user } = await s.render('/recurring')
    await user.click(await screen.findByRole('button', { name: 'Desativar Aluguel' }))
    await waitFor(() => expect(s.calls.deactivated).toBe('r1'))
    await user.click(screen.getByRole('button', { name: 'Ativar Freela' }))
    await waitFor(() => expect(s.calls.activated).toBe('r2'))
  })

  it('recurring_activateArchivedReference_showsError', async () => {
    const s = setup([
      http.post(`${api}/recurring-rules/:id/activate`, () => problem(409, 'CATEGORY_ARCHIVED')),
    ])
    const { user } = await s.render('/recurring')
    await user.click(await screen.findByRole('button', { name: 'Ativar Freela' }))
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).not.toContain('raw')
    expect(alert.textContent).toContain('arquivada')
  })

  it('recurring_editLocksKindFrequencyAndStart', async () => {
    const s = setup()
    const { user } = await s.mount('/recurring')
    await user.click(await screen.findByRole('button', { name: 'Editar Aluguel' }))
    const dialog = await screen.findByRole('dialog', { name: 'Editar recorrência' })
    expect(within(dialog).queryByRole('radio')).toBeNull()
    expect(within(dialog).queryByLabelText('Frequência')).toBeNull()
    expect(within(dialog).queryByLabelText('Início')).toBeNull()
    const locked = norm(dialog.textContent)
    expect(locked).toContain('Despesa')
    expect(locked).toContain('Mensal')
    expect(locked).toContain('05/01/2026')
    expect(locked).toContain('crie outra recorrência')
    // an edit never announces generation
    expect(within(dialog).queryByRole('status')).toBeNull()

    const amount = within(dialog).getByLabelText('Valor')
    await user.clear(amount)
    await user.type(amount, '1300')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    await waitFor(() =>
      expect(s.calls.put).toEqual({
        amountCents: 130000,
        description: 'Aluguel',
        categoryId: 'c1',
      }),
    )
  })

  it('recurring_pastStartShowsImmediateGenerationNotice', async () => {
    const s = setup()
    const { user } = await s.mount('/recurring?x=1')
    await user.click((await screen.findAllByRole('button', { name: 'Nova recorrência' }))[0]!)
    const dialog = await screen.findByRole('dialog', { name: 'Nova recorrência' })
    // the start defaults to today, so occurrences up to today are generated now
    expect((await within(dialog).findByRole('status')).textContent).toContain(
      'as ocorrências até hoje serão geradas agora',
    )
    const start = within(dialog).getByLabelText('Início')
    await user.clear(start)
    await user.type(start, '2999-01-01')
    await waitFor(() => expect(within(dialog).queryByRole('status')).toBeNull())
  })

  it('recurring_createSendsAllFields', async () => {
    const s = setup()
    const { user } = await s.mount('/recurring')
    await user.click((await screen.findAllByRole('button', { name: 'Nova recorrência' }))[0]!)
    const dialog = await screen.findByRole('dialog', { name: 'Nova recorrência' })
    await user.type(within(dialog).getByLabelText('Valor'), '10')
    const category = within(dialog).getByLabelText('Categoria') as HTMLSelectElement
    await waitFor(() => expect(category.options.length).toBeGreaterThan(1))
    await user.selectOptions(category, 'c1')
    await user.selectOptions(within(dialog).getByLabelText('Frequência'), 'WEEKLY')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    await waitFor(() =>
      expect(s.calls.post).toEqual({
        amountCents: 1000,
        categoryId: 'c1',
        kind: 'EXPENSE',
        frequency: 'WEEKLY',
        startDate: todayIso(),
      }),
    )
  })

  it('recurring_endDateBeforeStartIsRejected', async () => {
    const s = setup()
    const { user } = await s.mount('/recurring')
    await user.click((await screen.findAllByRole('button', { name: 'Nova recorrência' }))[0]!)
    const dialog = await screen.findByRole('dialog', { name: 'Nova recorrência' })
    await user.type(within(dialog).getByLabelText('Valor'), '10')
    const category = within(dialog).getByLabelText('Categoria') as HTMLSelectElement
    await waitFor(() => expect(category.options.length).toBeGreaterThan(1))
    await user.selectOptions(category, 'c1')
    const end = within(dialog).getByLabelText('Fim (opcional)')
    await user.type(end, '2000-01-01')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(end.getAttribute('aria-invalid')).toBe('true'))
    expect(s.calls.post).toBeUndefined()
  })

  it('recurring_serverEndDateOutOfRange_mapsToField', async () => {
    const s = setup([
      http.post(`${api}/recurring-rules`, () =>
        HttpResponse.json(
          {
            status: 400,
            code: 'VALIDATION_FAILED',
            detail: 'raw',
            errors: [{ field: 'endDate', code: 'OutOfRange' }],
          },
          { status: 400 },
        ),
      ),
    ])
    const { user } = await s.mount('/recurring')
    await user.click((await screen.findAllByRole('button', { name: 'Nova recorrência' }))[0]!)
    const dialog = await screen.findByRole('dialog', { name: 'Nova recorrência' })
    await user.type(within(dialog).getByLabelText('Valor'), '10')
    const category = within(dialog).getByLabelText('Categoria') as HTMLSelectElement
    await waitFor(() => expect(category.options.length).toBeGreaterThan(1))
    await user.selectOptions(category, 'c1')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    const end = within(dialog).getByLabelText('Fim (opcional)')
    await waitFor(() => expect(end.getAttribute('aria-invalid')).toBe('true'))
    expect(dialog.textContent).toContain('fora do intervalo permitido')
  })

  it('recurring_deleteWarnsGeneratedTransactionsRemain', async () => {
    const s = setup()
    const { user } = await s.render('/recurring')
    await user.click(await screen.findByRole('button', { name: 'Excluir Aluguel' }))
    const dialog = await screen.findByRole('dialog', { name: 'Excluir recorrência?' })
    expect(dialog.textContent).toContain('lançamentos já gerados permanecem')
    await user.click(within(dialog).getByRole('button', { name: 'Excluir' }))
    await waitFor(() => expect(s.calls.deleted).toBe('r1'))
  })
})
