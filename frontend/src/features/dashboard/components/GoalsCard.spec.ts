import { screen, waitFor, within } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type { DashboardResponse, GoalResponse } from '@/shared/api/types'
import { addMonths, currentMonth, formatMonth } from '@/shared/lib/dates'
import { render } from '@/test/render'
import GoalsCard from './GoalsCard.vue'

const api = `${window.location.origin}/api/v1`
const norm = (s: string | null) => (s ?? '').replace(/\s/g, ' ')
const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, detail: 'raw' }, { status })

const goal = (over: Partial<GoalResponse> = {}): GoalResponse => ({
  id: 'g1',
  name: 'Videogame novo',
  targetCents: 100000,
  targetDate: null,
  monthlyPercent: 5,
  status: 'ACTIVE',
  savedCents: 40000,
  remainingCents: 60000,
  completedAt: null,
  createdAt: '2026-01-01T00:00:00Z',
  projection: { requiredMonthlyCents: null, projectedMonth: null, targetDatePassed: false },
  ...over,
})

const salary = { id: 'c2', name: 'Salário', archived: false, color: '#0000aa', icon: 'gift' }

const overview = (over: Partial<DashboardResponse['goals']> = {}) => ({
  goals: {
    month: '2026-11',
    salaryCategory: salary,
    salaryCents: 300000,
    totalSavedCents: 40000,
    goals: [goal()],
    completed: [],
    suggestions: [
      {
        goalId: 'g1',
        goalName: 'Videogame novo',
        month: '2026-11',
        monthlyPercent: 5,
        suggestedCents: 15000,
      },
    ],
    ...over,
  },
})
// Only `goals` is read by the card.
const withGoals = (over?: Partial<DashboardResponse['goals']>) =>
  overview(over) as unknown as DashboardResponse

const SESSION = {}
function mountCard(data: DashboardResponse, handlers: Parameters<typeof render>[1] = {}) {
  return render(GoalsCard, { session: SESSION, ...handlers }, { props: { data } })
}

describe('GoalsCard', () => {
  it('confirm_postsShownAmount', async () => {
    let body: unknown
    const { user } = await mountCard(withGoals(), {
      handlers: [
        http.post(`${api}/goals/g1/contributions`, async ({ request }) => {
          body = await request.json()
          return HttpResponse.json({}, { status: 201 })
        }),
      ],
    })
    expect(screen.getByText(/Sugestões de/)).toBeTruthy()
    expect(norm(screen.getByText(/Sugestões de/).textContent)).toContain('€ 3.000,00')
    await user.click(screen.getByRole('button', { name: 'Confirmar Videogame novo' }))
    // The amount on screen, not one the server would recompute from a salary changed meanwhile.
    await waitFor(() => expect(body).toEqual({ month: '2026-11', amountCents: 15000 }))
  })

  it('adjust_postsTypedAmount', async () => {
    let body: unknown
    const { user } = await mountCard(withGoals(), {
      handlers: [
        http.post(`${api}/goals/g1/contributions`, async ({ request }) => {
          body = await request.json()
          return HttpResponse.json({}, { status: 201 })
        }),
      ],
    })
    await user.click(screen.getByRole('button', { name: 'Ajustar Videogame novo' }))
    const input = screen.getByLabelText('Valor para Videogame novo')
    await user.click(input)
    await user.keyboard('{Control>}a{/Control}300')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))
    await waitFor(() => expect(body).toEqual({ month: '2026-11', amountCents: 30000 }))
  })

  it('skip_postsSkip', async () => {
    let body: unknown
    const { user } = await mountCard(withGoals(), {
      handlers: [
        http.post(`${api}/goals/g1/skips`, async ({ request }) => {
          body = await request.json()
          return HttpResponse.json({}, { status: 201 })
        }),
      ],
    })
    await user.click(screen.getByRole('button', { name: 'Pular Videogame novo' }))
    await waitFor(() => expect(body).toEqual({ month: '2026-11' }))
  })

  it('confirm_failure_showsErrorInline', async () => {
    const { user } = await mountCard(withGoals(), {
      handlers: [
        http.post(`${api}/goals/g1/contributions`, () =>
          problem(409, 'GOAL_MONTH_ALREADY_HANDLED'),
        ),
      ],
    })
    await user.click(screen.getByRole('button', { name: 'Confirmar Videogame novo' }))
    expect(await screen.findByRole('alert')).toBeTruthy()
  })

  it('noSalaryCategory_showsHint', async () => {
    await mountCard(withGoals({ salaryCategory: null, suggestions: [] }))
    const hint = screen.getByRole('link', {
      name: 'Escolha a categoria do salário para receber sugestões',
    })
    expect(hint.getAttribute('href')).toBe('/goals')
  })

  it('archivedSalaryCategory_showsHint', async () => {
    await mountCard(withGoals({ salaryCategory: { ...salary, archived: true }, suggestions: [] }))
    expect(
      screen.getByRole('link', { name: 'Escolha a categoria do salário para receber sugestões' }),
    ).toBeTruthy()
  })

  it('usableSalaryCategory_hidesHint', async () => {
    await mountCard(withGoals({ suggestions: [] }))
    expect(screen.queryByText('Escolha a categoria do salário para receber sugestões')).toBeNull()
  })

  it('empty_offersCreate', async () => {
    await mountCard(withGoals({ goals: [], suggestions: [] }))
    const cta = screen.getByRole('link', { name: 'Crie um objetivo' })
    expect(cta.getAttribute('href')).toBe('/goals?new=1')
  })

  it('progress_showsSavedOverTarget', async () => {
    await mountCard(withGoals({ suggestions: [] }))
    const bar = screen.getByRole('progressbar', { name: 'Videogame novo' })
    expect(bar.getAttribute('aria-valuenow')).toBe('40')
    const item = bar.closest('li') as HTMLElement
    expect(norm(item.textContent)).toContain('€ 400,00')
    expect(norm(item.textContent)).toContain('€ 1.000,00')
    expect(within(item).getByText('40%')).toBeTruthy()
    expect(screen.getByRole('link', { name: 'Ver todos os objetivos' }).getAttribute('href')).toBe(
      '/goals',
    )
  })

  it('reached_showsBadge', async () => {
    await mountCard(
      withGoals({ goals: [goal({ status: 'REACHED', savedCents: 100000 })], suggestions: [] }),
    )
    const item = screen.getByRole('progressbar').closest('li') as HTMLElement
    expect(within(item).getByText('Atingido')).toBeTruthy()
  })

  it.each([
    ['', 'Informe um valor maior que zero.'],
    ['0', 'Informe um valor maior que zero.'],
    ['700', 'O valor passa do que falta: € 600,00.'],
  ])('adjust_invalidAmount_%s_showsMessageWithoutRequest', async (typed, message) => {
    let posted = 0
    const { user } = await mountCard(withGoals(), {
      handlers: [
        http.post(`${api}/goals/g1/contributions`, () => {
          posted++
          return HttpResponse.json({}, { status: 201 })
        }),
      ],
    })
    await user.click(screen.getByRole('button', { name: 'Ajustar Videogame novo' }))
    const form = screen.getByLabelText('Valor para Videogame novo').closest('form') as HTMLElement
    expect(norm(form.textContent)).toContain('Faltam € 600,00')
    const input = screen.getByLabelText('Valor para Videogame novo')
    await user.click(input)
    await user.keyboard('{Control>}a{/Control}{Backspace}')
    if (typed) await user.keyboard(typed)
    await user.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(norm((await screen.findByRole('alert')).textContent)).toBe(message)
    expect(posted).toBe(0)
  })

  it('confirm_failure_refreshesGoalsAndDashboard', async () => {
    const { user, queryClient } = await mountCard(withGoals(), {
      handlers: [
        http.post(`${api}/goals/g1/contributions`, () => problem(409, 'GOAL_MONTH_TAKEN')),
      ],
    })
    const spy = vi.spyOn(queryClient, 'invalidateQueries')
    await user.click(screen.getByRole('button', { name: 'Confirmar Videogame novo' }))
    await screen.findByRole('alert')
    await waitFor(() => {
      const filters = spy.mock.calls.map(([f]) => f)
      expect(filters).toContainEqual({ queryKey: ['goals'] })
      expect(filters).toContainEqual({ queryKey: ['dashboard'] })
    })
  })

  it('confirm_amountInvalid_showsAccurateMessage', async () => {
    const { user } = await mountCard(withGoals(), {
      handlers: [
        http.post(`${api}/goals/g1/contributions`, () => problem(400, 'GOAL_AMOUNT_INVALID')),
      ],
    })
    await user.click(screen.getByRole('button', { name: 'Confirmar Videogame novo' }))
    expect((await screen.findByRole('alert')).textContent).toBe(
      'Valor inválido para este objetivo: ele ficaria negativo ou acima da meta.',
    )
  })

  it('noSalaryThisMonth_saysSo', async () => {
    const month = currentMonth()
    await mountCard(withGoals({ month, salaryCents: 0, suggestions: [] }))
    expect(norm(screen.getByText(/^Nenhum salário em/).textContent)).toBe(
      `Nenhum salário em ${formatMonth(month, 'pt-BR')} na categoria Salário.`,
    )
  })

  it('noSalaryThisMonth_futureMonthOrNoPercent_staysQuiet', async () => {
    await mountCard(
      withGoals({ month: addMonths(currentMonth(), 1), salaryCents: 0, suggestions: [] }),
    )
    expect(screen.queryByText(/^Nenhum salário em/)).toBeNull()
  })

  it('noSalaryThisMonth_goalsWithoutPercent_staysQuiet', async () => {
    await mountCard(
      withGoals({
        month: currentMonth(),
        salaryCents: 0,
        goals: [goal({ monthlyPercent: 0 })],
        suggestions: [],
      }),
    )
    expect(screen.queryByText(/^Nenhum salário em/)).toBeNull()
  })

  it('adjust_focusesAmount_andCancelReturnsFocusAndClearsErrors', async () => {
    const { user } = await mountCard(withGoals())
    const adjust = screen.getByRole('button', { name: 'Ajustar Videogame novo' })
    await user.click(adjust)
    const input = screen.getByLabelText('Valor para Videogame novo')
    await waitFor(() => expect(document.activeElement).toBe(input))
    await user.keyboard('{Control>}a{/Control}700')
    await user.click(screen.getByRole('button', { name: 'Salvar' }))
    await screen.findByRole('alert')
    await user.click(screen.getByRole('button', { name: 'Cancelar' }))
    await waitFor(() =>
      expect(document.activeElement).toBe(
        screen.getByRole('button', { name: 'Ajustar Videogame novo' }),
      ),
    )
    expect(screen.queryByRole('alert')).toBeNull()
    await user.click(screen.getByRole('button', { name: 'Ajustar Videogame novo' }))
    expect(screen.queryByRole('alert')).toBeNull()
  })

  it('adjust_save_movesFocusToNextSuggestion', async () => {
    const second = goal({ id: 'g2', name: 'Viagem' })
    const { user } = await mountCard(
      withGoals({
        goals: [goal(), second],
        suggestions: [
          {
            goalId: 'g1',
            goalName: 'Videogame novo',
            month: '2026-11',
            monthlyPercent: 5,
            suggestedCents: 15000,
          },
          {
            goalId: 'g2',
            goalName: 'Viagem',
            month: '2026-11',
            monthlyPercent: 5,
            suggestedCents: 15000,
          },
        ],
      }),
      {
        handlers: [
          http.post(`${api}/goals/g1/contributions`, () => HttpResponse.json({}, { status: 201 })),
        ],
      },
    )
    await user.click(screen.getByRole('button', { name: 'Ajustar Videogame novo' }))
    await user.click(screen.getByRole('button', { name: 'Salvar' }))
    await waitFor(() =>
      expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Confirmar Viagem' })),
    )
  })

  describe('after the partner already decided, or with several suggestions', () => {
    const suggestion = (goalId: string, goalName: string) => ({
      goalId,
      goalName,
      month: '2026-11',
      monthlyPercent: 5,
      suggestedCents: 15000,
    })
    const three = () =>
      withGoals({
        goals: [goal(), goal({ id: 'g2', name: 'Viagem' }), goal({ id: 'g3', name: 'Reserva' })],
        suggestions: [
          suggestion('g1', 'Videogame novo'),
          suggestion('g2', 'Viagem'),
          suggestion('g3', 'Reserva'),
        ],
      })
    /** The dashboard refetch that follows every goal mutation, without the given suggestion. */
    const without = (data: DashboardResponse, goalId: string) =>
      withGoals({
        ...data.goals,
        suggestions: data.goals.suggestions.filter((s) => s.goalId !== goalId),
      })

    /** Mounts the card; the POST to `path` answers once the refetch removed `goalId`'s row. */
    async function mountAnsweringAfterRefetch(
      data: DashboardResponse,
      path: string,
      goalId: string,
      reply: () => Response,
    ) {
      const mounted: { wrapper?: { setProps: (p: { data: DashboardResponse }) => Promise<void> } } =
        {}
      const r = await mountCard(data, {
        handlers: [
          http.post(`${api}/goals/${path}`, async () => {
            await mounted.wrapper?.setProps({ data: without(data, goalId) })
            return reply()
          }),
        ],
      })
      mounted.wrapper = r.wrapper as unknown as typeof mounted.wrapper
      return r
    }

    it('confirm_failure_whenTheRowIsGone_keepsTheMessage', async () => {
      const data = withGoals()
      const r = await mountAnsweringAfterRefetch(data, 'g1/contributions', 'g1', () =>
        problem(409, 'GOAL_MONTH_TAKEN'),
      )
      await r.user.click(screen.getByRole('button', { name: 'Confirmar Videogame novo' }))
      const alert = await screen.findByRole('alert')
      expect(alert.textContent).toContain('Videogame novo')
      expect(screen.queryByRole('button', { name: 'Confirmar Videogame novo' })).toBeNull()
    })

    it('confirm_success_movesFocusToTheNextSuggestion', async () => {
      const data = three()
      const r = await mountAnsweringAfterRefetch(data, 'g2/contributions', 'g2', () =>
        HttpResponse.json({}, { status: 201 }),
      )
      await r.user.click(screen.getByRole('button', { name: 'Confirmar Viagem' }))
      await waitFor(() =>
        expect(document.activeElement).toBe(
          screen.getByRole('button', { name: 'Confirmar Reserva' }),
        ),
      )
    })

    it('adjust_save_afterTheRowIsGone_focusesTheFollowingSuggestion', async () => {
      const data = three()
      const r = await mountAnsweringAfterRefetch(data, 'g2/contributions', 'g2', () =>
        HttpResponse.json({}, { status: 201 }),
      )
      await r.user.click(screen.getByRole('button', { name: 'Ajustar Viagem' }))
      await r.user.click(screen.getByRole('button', { name: 'Salvar' }))
      await waitFor(() =>
        expect(document.activeElement).toBe(
          screen.getByRole('button', { name: 'Confirmar Reserva' }),
        ),
      )
    })

    it('skip_lastSuggestion_movesFocusToTheTitle', async () => {
      const data = withGoals()
      const r = await mountAnsweringAfterRefetch(data, 'g1/skips', 'g1', () =>
        HttpResponse.json({}, { status: 201 }),
      )
      await r.user.click(screen.getByRole('button', { name: 'Pular Videogame novo' }))
      await waitFor(() => expect(document.activeElement?.textContent?.trim()).toBe('Objetivos'))
    })

    it('adjust_serverFailure_showsErrorAndKeepsTheForm', async () => {
      const { user } = await mountCard(withGoals(), {
        handlers: [
          http.post(`${api}/goals/g1/contributions`, () => problem(400, 'GOAL_AMOUNT_INVALID')),
        ],
      })
      await user.click(screen.getByRole('button', { name: 'Ajustar Videogame novo' }))
      await user.click(screen.getByRole('button', { name: 'Salvar' }))
      expect((await screen.findByRole('alert')).textContent).toContain('Valor inválido')
      const input = screen.getByLabelText('Valor para Videogame novo')
      await waitFor(() => expect(document.activeElement).toBe(input))
    })

    it('skip_failure_keepsFocusOnSkip', async () => {
      const { user } = await mountCard(withGoals(), {
        handlers: [http.post(`${api}/goals/g1/skips`, () => problem(409, 'GOAL_COMPLETED'))],
      })
      await user.click(screen.getByRole('button', { name: 'Pular Videogame novo' }))
      await screen.findByRole('alert')
      await waitFor(() =>
        expect(document.activeElement).toBe(
          screen.getByRole('button', { name: 'Pular Videogame novo' }),
        ),
      )
    })
  })
})
