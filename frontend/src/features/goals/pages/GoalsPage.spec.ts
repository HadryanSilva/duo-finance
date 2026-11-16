import { screen, waitFor, within } from '@testing-library/dom'
import { http, HttpResponse, type RequestHandler } from 'msw'
import { afterEach, describe, expect, it } from 'vitest'
import App from '@/app/App.vue'
import { currentMonth } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { render, renderRoute, testUser } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, detail: 'raw' }, { status })
const norm = (s: string | null) => (s ?? '').replace(/\s/g, ' ')

const ANA = '00000000-0000-4000-8000-000000000001'
const account = {
  id: testUser.accountId,
  name: 'Ana e Bia',
  currency: 'EUR',
  members: [
    { userId: ANA, displayName: 'Ana Souza', email: 'a@x.com', joinedAt: '2026-01-01T00:00:00Z' },
  ],
  pendingInvitation: null,
}

const cat = { color: '#0000aa', systemDefault: false, kind: 'INCOME', icon: 'gift' }
const salario = { ...cat, id: 'c2', name: 'Salário', archived: false }
const freelance = { ...cat, id: 'c3', name: 'Freelance', archived: false }
const antigo = { ...cat, id: 'c9', name: 'Salário antigo', archived: true }
const ref = (c: typeof salario) => ({
  id: c.id,
  name: c.name,
  archived: c.archived,
  color: c.color,
  icon: c.icon,
})

const goal = {
  id: 'g1',
  name: 'Videogame novo',
  targetCents: 100000,
  targetDate: '2027-06-01',
  monthlyPercent: 5,
  status: 'ACTIVE',
  savedCents: 40000,
  remainingCents: 60000,
  completedAt: null,
  createdAt: '2026-01-01T00:00:00Z',
  projection: { requiredMonthlyCents: 7500, projectedMonth: null },
}
const done = { ...goal, id: 'g2', name: 'Viagem', status: 'COMPLETED', completedAt: '2026-10-01' }

const movements = [
  {
    id: 'm1',
    type: 'CONTRIBUTION',
    amountCents: 5000,
    month: '2026-10',
    note: null,
    createdBy: { id: ANA, displayName: 'Ana Souza' },
    createdAt: '2026-10-05T10:00:00Z',
  },
  {
    id: 'm2',
    type: 'DEPOSIT',
    amountCents: 35000,
    month: null,
    note: 'Bônus',
    createdBy: { id: ANA, displayName: 'Ana Souza' },
    createdAt: '2026-10-07T10:00:00Z',
  },
]

interface Opts {
  goals?: object[]
  completed?: object[]
  settingsCategory?: ReturnType<typeof ref> | null
  extra?: RequestHandler[]
}

function setup({ goals = [goal], completed = [], settingsCategory = null, extra = [] }: Opts = {}) {
  const calls: Record<string, unknown> = {}
  let isDone = false
  const handlers = [
    ...extra,
    http.get(`${api}/account`, () => HttpResponse.json(account)),
    http.get(`${api}/categories`, ({ request }) => {
      const p = new URL(request.url).searchParams
      const all = [salario, freelance, ...(p.get('includeArchived') === 'true' ? [antigo] : [])]
      const kind = p.get('kind')
      return HttpResponse.json(all.filter((c) => !kind || c.kind === kind))
    }),
    http.get(`${api}/goals/settings`, () =>
      HttpResponse.json({ salaryCategory: settingsCategory }),
    ),
    http.put(`${api}/goals/settings`, async ({ request }) => {
      calls.settings = await request.json()
      return HttpResponse.json({ salaryCategory: ref(salario) })
    }),
    http.get(`${api}/goals`, ({ request }) => {
      calls.month = new URL(request.url).searchParams.get('month')
      return HttpResponse.json({
        month: currentMonth(),
        salaryCategory: settingsCategory,
        salaryCents: 300000,
        totalSavedCents: 40000,
        goals: isDone ? [] : goals,
        completed: isDone ? [{ ...goal, status: 'COMPLETED' }] : completed,
        suggestions: [],
      })
    }),
    http.post(`${api}/goals`, async ({ request }) => {
      calls.post = await request.json()
      return HttpResponse.json(goal, { status: 201 })
    }),
    http.post(`${api}/goals/:id/complete`, ({ params }) => {
      calls.completed = params.id
      isDone = true
      return HttpResponse.json(goal)
    }),
    http.post(`${api}/goals/:id/reopen`, ({ params }) => {
      calls.reopened = params.id
      return HttpResponse.json(goal)
    }),
    http.post(`${api}/goals/g1/withdrawals`, async ({ request }) => {
      calls.withdraw = await request.json()
      return HttpResponse.json(movements[0], { status: 201 })
    }),
    http.put(`${api}/goals/:id`, async ({ request, params }) => {
      calls.put = { id: params.id, body: await request.json() }
      return HttpResponse.json(goal)
    }),
    http.delete(`${api}/goals/:id`, ({ params }) => {
      calls.deleted = params.id
      return new HttpResponse(null, { status: 204 })
    }),
    http.post(`${api}/goals/g1/deposits`, async ({ request }) => {
      calls.deposit = await request.json()
      return HttpResponse.json(goal, { status: 201 })
    }),
    http.get(`${api}/goals/:id/movements`, () => HttpResponse.json(movements)),
    http.delete(`${api}/goals/:id/movements/:mid`, ({ params }) => {
      calls.undone = `${String(params.id)}/${String(params.mid)}`
      return new HttpResponse(null, { status: 204 })
    }),
  ]
  return {
    calls,
    render: (route = '/goals', locale?: Locale) =>
      renderRoute({ route, session: {}, handlers, locale }),
    mount: (route: string) => render(App, { route, session: {}, handlers }),
  }
}

/** A native date input set like a picker would, which typing cannot do in jsdom. */
function setDate(input: HTMLElement, value: string) {
  ;(input as HTMLInputElement).value = value
  input.dispatchEvent(new Event('input', { bubbles: true }))
}

function yesterday(): string {
  const d = new Date()
  d.setDate(d.getDate() - 1)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`
}

const row = async (name: string) => {
  const heading = await screen.findByText(name)
  return heading.closest('li') as HTMLElement
}

describe('GoalsPage', () => {
  it('goals_listShowsProgressAndProjection', async () => {
    await setup().render()
    const r = await row('Videogame novo')
    const text = norm(r.textContent)
    expect(text).toContain('€ 400,00')
    expect(text).toContain('€ 1.000,00')
    expect(text).toContain('40%')
    expect(text).toContain('Faltam € 600,00')
    expect(text).toContain('5% do salário/mês')
    expect(text).toContain('Para chegar até 1 de junho de 2027 faltam € 75,00/mês')
  })

  it('goals_projectionWithoutDate_showsPace', async () => {
    const g = {
      ...goal,
      targetDate: null,
      projection: { requiredMonthlyCents: null, projectedMonth: '2027-03' },
    }
    await setup({ goals: [g] }).render()
    const r = await row('Videogame novo')
    expect(norm(r.textContent)).toContain('No ritmo atual chega em março de 2027')
  })

  it('goals_emptyState_offersCreate', async () => {
    const { user } = await setup({ goals: [] }).render()
    await screen.findByText('Nenhum objetivo ainda')
    const buttons = screen.getAllByRole('button', { name: 'Novo objetivo' })
    await user.click(buttons[buttons.length - 1] as HTMLElement)
    expect(await screen.findByRole('dialog', { name: 'Novo objetivo' })).toBeTruthy()
  })

  it('goals_newQuery_opensForm', async () => {
    await setup().mount('/goals?new=1')
    expect(await screen.findByRole('dialog', { name: 'Novo objetivo' })).toBeTruthy()
  })

  it('goals_create_sendsBody', async () => {
    const s = setup()
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Novo objetivo' }))
    const dialog = await screen.findByRole('dialog', { name: 'Novo objetivo' })
    await user.type(within(dialog).getByLabelText('Nome'), 'Videogame novo')
    await user.type(within(dialog).getByLabelText('Valor alvo'), '500')
    await user.type(within(dialog).getByLabelText('Porcentagem do salário'), '5')
    expect(within(dialog).getByText('0 = sem sugestão mensal')).toBeTruthy()
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    await waitFor(() =>
      expect(s.calls.post).toEqual({
        name: 'Videogame novo',
        targetCents: 50000,
        monthlyPercent: 5,
      }),
    )
  })

  it('goals_percentCap_showsFieldError', async () => {
    const s = setup({
      extra: [http.post(`${api}/goals`, () => problem(400, 'GOAL_PERCENT_CAP'))],
    })
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Novo objetivo' }))
    const dialog = await screen.findByRole('dialog', { name: 'Novo objetivo' })
    await user.type(within(dialog).getByLabelText('Nome'), 'Casa')
    await user.type(within(dialog).getByLabelText('Valor alvo'), '500')
    await user.type(within(dialog).getByLabelText('Porcentagem do salário'), '90')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    const input = within(dialog).getByLabelText('Porcentagem do salário')
    await waitFor(() => {
      const id = input.getAttribute('aria-describedby') ?? ''
      expect(document.getElementById(id)?.textContent).toContain('passaria de 100%')
    })
  })

  it('goals_salaryCategory_saves', async () => {
    const s = setup()
    const { user } = await s.render()
    const select = await screen.findByLabelText('O salário vem da categoria')
    await within(select).findByRole('option', { name: 'Salário' })
    await user.selectOptions(select, 'Salário')
    await waitFor(() => expect(s.calls.settings).toEqual({ salaryCategoryId: 'c2' }))
  })

  it('goals_salaryCategory_failedSave_showsSavedChoiceAgain', async () => {
    const s = setup({
      extra: [http.put(`${api}/goals/settings`, () => problem(400, 'VALIDATION'))],
    })
    const { user } = await s.render()
    const select = (await screen.findByLabelText('O salário vem da categoria')) as HTMLSelectElement
    await within(select).findByRole('option', { name: 'Salário' })
    await user.selectOptions(select, 'Salário')
    await screen.findByRole('alert')
    expect(select.value).toBe('')
  })

  it('goals_completedRow_hidesRemaining', async () => {
    await setup({ goals: [], completed: [done] }).render()
    const r = await row('Viagem')
    expect(norm(r.textContent)).not.toContain('Faltam')
  })

  it('goals_projectionPastDate_saysSoWithPace', async () => {
    const g = {
      ...goal,
      targetDate: '2026-01-31',
      projection: { requiredMonthlyCents: null, projectedMonth: '2027-03', targetDatePassed: true },
    }
    await setup({ goals: [g] }).render()
    const r = await row('Videogame novo')
    expect(norm(r.textContent)).toContain(
      'A data-alvo (31 de janeiro de 2026) já passou. No ritmo atual chega em março de 2027',
    )
    expect(norm(r.textContent)).not.toContain('Para chegar até')
  })

  it('goals_history_en_saysInForMonthsAndOnForDates', async () => {
    const { user } = await setup().render('/goals', 'en')
    await user.click(await screen.findByRole('button', { name: 'History of Videogame novo' }))
    const dialog = await screen.findByRole('dialog', { name: 'History' })
    const names = (await within(dialog).findAllByRole('button', { name: /^Undo / })).map((b) =>
      norm(b.getAttribute('aria-label')),
    )
    expect(names[0]).toMatch(/^Undo monthly contribution of €50\.00 in October 2026$/)
    expect(names[1]).toMatch(/^Undo deposit of €350\.00 on /)
    await user.click(within(dialog).getAllByRole('button', { name: /^Undo / })[0] as HTMLElement)
    const confirm = await screen.findByRole('dialog', { name: 'Undo movement?' })
    expect(norm(confirm.textContent)).toContain('of €50.00 in October 2026?')
  })

  it('goals_salaryCategory_archivedShowsWarning', async () => {
    await setup({ settingsCategory: ref(antigo) }).render()
    expect(await screen.findByText(/categoria de salário está arquivada/)).toBeTruthy()
    const select = await screen.findByLabelText('O salário vem da categoria')
    expect(within(select).getByRole('option', { name: /Salário antigo/ })).toBeTruthy()
  })

  it('goals_withdraw_sendsAmountAndNote', async () => {
    const s = setup()
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Resgatar de Videogame novo' }))
    const dialog = await screen.findByRole('dialog', { name: 'Resgatar' })
    expect(norm(dialog.textContent)).toContain('Disponível: € 400,00')
    await user.type(within(dialog).getByLabelText('Valor'), '100')
    await user.type(within(dialog).getByLabelText('Observação (opcional)'), 'Emergência')
    await user.click(within(dialog).getByRole('button', { name: 'Resgatar' }))
    await waitFor(() =>
      expect(s.calls.withdraw).toEqual({ amountCents: 10000, note: 'Emergência' }),
    )
  })

  it('goals_deposit_showsRemaining', async () => {
    const { user } = await setup().render()
    await user.click(
      await screen.findByRole('button', { name: 'Adicionar valor a Videogame novo' }),
    )
    const dialog = await screen.findByRole('dialog', { name: 'Adicionar valor' })
    expect(norm(dialog.textContent)).toContain('Faltam: € 600,00')
  })

  it('goals_history_undo', async () => {
    const s = setup()
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Histórico de Videogame novo' }))
    const dialog = await screen.findByRole('dialog', { name: 'Histórico' })
    const items = await within(dialog).findAllByRole('listitem')
    expect(items).toHaveLength(2)
    expect(within(items[0] as HTMLElement).getByRole('img', { name: 'Ana Souza' })).toBeTruthy()
    expect(norm(items[1]?.textContent ?? '')).toContain('Bônus')
    await user.click(
      within(dialog).getByRole('button', {
        name: /^Desfazer contribuição mensal de €\s50,00 em outubro de 2026$/,
      }),
    )
    const confirm = await screen.findByRole('dialog', { name: 'Desfazer movimentação?' })
    expect(norm(confirm.textContent)).toContain('contribuição mensal de € 50,00')
    await user.click(within(confirm).getByRole('button', { name: 'Desfazer' }))
    await waitFor(() => expect(s.calls.undone).toBe('g1/m1'))
  })

  it('goals_complete_confirmsReleasedAmount', async () => {
    const s = setup()
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Concluir Videogame novo' }))
    const confirm = await screen.findByRole('dialog', { name: 'Concluir Videogame novo?' })
    expect(norm(confirm.textContent)).toContain('€ 400,00 deixam de ficar separados.')
    await user.click(within(confirm).getByRole('button', { name: 'Concluir' }))
    await waitFor(() => expect(s.calls.completed).toBe('g1'))
    await screen.findByText('Concluídos')
    await user.click(await screen.findByRole('button', { name: 'Reabrir Videogame novo' }))
    await waitFor(() => expect(s.calls.reopened).toBe('g1'))
  })

  it('goals_history_undoButtonsHaveUniqueNames', async () => {
    const { user } = await setup().render()
    await user.click(await screen.findByRole('button', { name: 'Histórico de Videogame novo' }))
    const dialog = await screen.findByRole('dialog', { name: 'Histórico' })
    const names = (await within(dialog).findAllByRole('button', { name: /^Desfazer / })).map((b) =>
      norm(b.getAttribute('aria-label')),
    )
    expect(new Set(names).size).toBe(2)
    expect(names[1]).toContain('depósito de € 350,00')
  })

  it('goals_delete_activeGoalSaysAmountIsReleased', async () => {
    const { user } = await setup().render()
    await user.click(await screen.findByRole('button', { name: 'Excluir Videogame novo' }))
    const confirm = await screen.findByRole('dialog', { name: 'Excluir Videogame novo?' })
    expect(norm(confirm.textContent)).toContain('€ 400,00 deixam de ficar separados')
  })

  it('goals_delete_completedGoalDoesNotClaimRelease', async () => {
    const { user } = await setup({ goals: [], completed: [done] }).render()
    await user.click(await screen.findByRole('button', { name: 'Excluir Viagem' }))
    const confirm = await screen.findByRole('dialog', { name: 'Excluir Viagem?' })
    expect(confirm.textContent).toContain('O histórico será apagado.')
    expect(confirm.textContent).not.toContain('deixam de ficar separados')
  })

  it('goals_completedRows_haveNoUndoInHistory', async () => {
    const { user } = await setup({ goals: [], completed: [done] }).render()
    await user.click(await screen.findByRole('button', { name: 'Histórico de Viagem' }))
    const dialog = await screen.findByRole('dialog', { name: 'Histórico' })
    await within(dialog).findAllByRole('listitem')
    expect(within(dialog).queryByRole('button', { name: 'Desfazer' })).toBeNull()
  })

  it('goals_create_pastDate_rejectedBeforeSending', async () => {
    const s = setup()
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Novo objetivo' }))
    const dialog = await screen.findByRole('dialog', { name: 'Novo objetivo' })
    await user.type(within(dialog).getByLabelText('Nome'), 'Casa')
    await user.type(within(dialog).getByLabelText('Valor alvo'), '500')
    setDate(within(dialog).getByLabelText('Data alvo (opcional)'), yesterday())
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    expect(await within(dialog).findByText('Escolha hoje ou uma data futura.')).toBeTruthy()
    expect(s.calls.post).toBeUndefined()
  })

  it('goals_create_withoutDate_omitsIt', async () => {
    const s = setup()
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Novo objetivo' }))
    const dialog = await screen.findByRole('dialog', { name: 'Novo objetivo' })
    await user.type(within(dialog).getByLabelText('Nome'), 'Casa')
    await user.type(within(dialog).getByLabelText('Valor alvo'), '500')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(s.calls.post).toBeDefined())
    expect(s.calls.post).not.toHaveProperty('targetDate')
  })

  it('goals_edit_keepsAnAlreadyPassedDate_andSendsTheChange', async () => {
    const s = setup({ goals: [{ ...goal, targetDate: '2020-01-31' }] })
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Editar Videogame novo' }))
    const dialog = await screen.findByRole('dialog', { name: 'Editar objetivo' })
    const name = within(dialog).getByLabelText('Nome')
    await user.clear(name)
    await user.type(name, 'Console')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    await waitFor(() =>
      expect(s.calls.put).toEqual({
        id: 'g1',
        body: {
          name: 'Console',
          targetCents: 100000,
          targetDate: '2020-01-31',
          monthlyPercent: 5,
        },
      }),
    )
  })

  it('goals_create_nameTaken_showsFieldError', async () => {
    const s = setup({
      extra: [http.post(`${api}/goals`, () => problem(409, 'GOAL_NAME_TAKEN'))],
    })
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Novo objetivo' }))
    const dialog = await screen.findByRole('dialog', { name: 'Novo objetivo' })
    await user.type(within(dialog).getByLabelText('Nome'), 'Videogame novo')
    await user.type(within(dialog).getByLabelText('Valor alvo'), '500')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    const input = within(dialog).getByLabelText('Nome')
    await waitFor(() => {
      const id = input.getAttribute('aria-describedby') ?? ''
      expect(document.getElementById(id)?.textContent).toBeTruthy()
    })
  })

  it('goals_delete_sendsDelete', async () => {
    const s = setup()
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Excluir Videogame novo' }))
    const confirm = await screen.findByRole('dialog', { name: 'Excluir Videogame novo?' })
    await user.click(within(confirm).getByRole('button', { name: 'Excluir' }))
    await waitFor(() => expect(s.calls.deleted).toBe('g1'))
  })

  it('goals_deposit_sendsAmountAndNote', async () => {
    const s = setup()
    const { user } = await s.render()
    await user.click(
      await screen.findByRole('button', { name: 'Adicionar valor a Videogame novo' }),
    )
    const dialog = await screen.findByRole('dialog', { name: 'Adicionar valor' })
    await user.type(within(dialog).getByLabelText('Valor'), '200')
    await user.type(within(dialog).getByLabelText('Observação (opcional)'), '13º')
    await user.click(within(dialog).getByRole('button', { name: 'Adicionar' }))
    await waitFor(() => expect(s.calls.deposit).toEqual({ amountCents: 20000, note: '13º' }))
  })
})

describe('GoalsPage on a phone', () => {
  afterEach(() => {
    window.innerWidth = 1024 // jsdom's default
  })

  it('goals_mobile_actionsMenu', async () => {
    window.innerWidth = 375
    const { user } = await setup().render()
    await user.click(await screen.findByRole('button', { name: 'Ações para Videogame novo' }))
    await waitFor(() =>
      expect(
        [...document.querySelectorAll('[role="menuitem"]')].map((el) => el.textContent?.trim()),
      ).toEqual(['Adicionar valor', 'Resgatar', 'Histórico', 'Editar', 'Concluir', 'Excluir']),
    )
  })
})
