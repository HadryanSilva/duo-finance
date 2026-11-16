import { screen, waitFor, within } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { DashboardResponse, GoalResponse } from '@/shared/api/types'
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
  projection: { requiredMonthlyCents: null, projectedMonth: null },
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
  it('confirm_postsSuggestedMonth', async () => {
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
    await waitFor(() => expect(body).toEqual({ month: '2026-11' }))
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
})
