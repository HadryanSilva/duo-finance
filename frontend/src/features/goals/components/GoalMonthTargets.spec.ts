import { screen, waitFor, within } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { GoalMonthTarget, GoalResponse, GoalsOverview } from '@/shared/api/types'
import { addMonths, currentMonth, formatMonth, todayIso } from '@/shared/lib/dates'
import { render } from '@/test/render'
import GoalMonthTargets from './GoalMonthTargets.vue'

const api = `${window.location.origin}/api/v1`
const norm = (s: string | null) => (s ?? '').replace(/\s/g, ' ')
const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, detail: 'raw' }, { status })

const goal = (over: Partial<GoalResponse> = {}): GoalResponse => ({
  id: 'g1',
  name: 'Videogame novo',
  targetCents: 100000,
  targetDate: null,
  monthlyPercent: 10,
  status: 'ACTIVE',
  savedCents: 40000,
  remainingCents: 60000,
  completedAt: null,
  createdAt: '2026-01-01T00:00:00Z',
  projection: { requiredMonthlyCents: null, projectedMonth: null, targetDatePassed: false },
  ...over,
})

const target = (over: Partial<GoalMonthTarget> = {}): GoalMonthTarget => ({
  goalId: 'g1',
  goalName: 'Videogame novo',
  month: '2026-11',
  monthlyPercent: 10,
  targetCents: 30000,
  contributedCents: 15000,
  skipped: false,
  ...over,
})

const salary = { id: 'c2', name: 'Salário', archived: false, color: '#0000aa', icon: 'gift' }

const overview = (over: Partial<GoalsOverview> = {}): GoalsOverview => ({
  month: '2026-11',
  salaryCategory: salary,
  salaryCents: 300000,
  totalSavedCents: 40000,
  goals: [goal()],
  completed: [],
  monthTargets: [target()],
  ...over,
})

const SESSION = {}
function mountTargets(data: GoalsOverview, handlers: Parameters<typeof render>[1] = {}) {
  return render(GoalMonthTargets, { session: SESSION, ...handlers }, { props: { overview: data } })
}

describe('GoalMonthTargets', () => {
  it('showsProgress', async () => {
    await mountTargets(overview())
    const bar = screen.getByRole('progressbar', { name: 'Videogame novo' })
    expect(bar.getAttribute('aria-valuenow')).toBe('50')
    const item = bar.closest('li') as HTMLElement
    expect(norm(item.textContent)).toContain('150,00')
    expect(norm(item.textContent)).toContain('300,00')
  })

  it('met_showsCheck_noButtons', async () => {
    await mountTargets(overview({ monthTargets: [target({ contributedCents: 30000 })] }))
    expect(screen.getByText('Meta do mês atingida')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Registrar/ })).toBeNull()
    expect(screen.queryByRole('button', { name: /Pular/ })).toBeNull()
  })

  it('register_prefillsRemaining', async () => {
    const { user } = await mountTargets(overview())
    await user.click(screen.getByRole('button', { name: 'Registrar Videogame novo' }))
    const dialog = await screen.findByRole('dialog', { name: 'Registrar contribuição' })
    const amount = within(dialog).getByLabelText('Valor') as HTMLInputElement
    expect(norm(amount.value)).toContain('150,00')
  })

  it('register_postsPrefilledAmountWithTodayAsDate', async () => {
    let body: unknown
    const { user } = await mountTargets(overview(), {
      handlers: [
        http.post(`${api}/goals/g1/contributions`, async ({ request }) => {
          body = await request.json()
          return HttpResponse.json({}, { status: 201 })
        }),
      ],
    })
    await user.click(screen.getByRole('button', { name: 'Registrar Videogame novo' }))
    const dialog = await screen.findByRole('dialog', { name: 'Registrar contribuição' })
    await user.click(within(dialog).getByRole('button', { name: 'Registrar' }))
    await waitFor(() => expect(body).toEqual({ amountCents: 15000, date: todayIso() }))
  })

  it('skip_postsSkip', async () => {
    let body: unknown
    const { user } = await mountTargets(
      overview({ monthTargets: [target({ contributedCents: 0 })] }),
      {
        handlers: [
          http.post(`${api}/goals/g1/skips`, async ({ request }) => {
            body = await request.json()
            return HttpResponse.json({}, { status: 201 })
          }),
        ],
      },
    )
    await user.click(screen.getByRole('button', { name: 'Pular Videogame novo' }))
    await waitFor(() => expect(body).toEqual({ month: '2026-11' }))
  })

  it('skip_failure_showsErrorInline', async () => {
    const { user } = await mountTargets(
      overview({ monthTargets: [target({ contributedCents: 0 })] }),
      { handlers: [http.post(`${api}/goals/g1/skips`, () => problem(409, 'GOAL_MONTH_TAKEN'))] },
    )
    await user.click(screen.getByRole('button', { name: 'Pular Videogame novo' }))
    expect((await screen.findByRole('alert')).textContent).toContain('já foi confirmado ou pulado')
  })

  it('skip_hiddenOnceContributed', async () => {
    await mountTargets(overview())
    expect(screen.getByRole('button', { name: 'Registrar Videogame novo' })).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Pular Videogame novo' })).toBeNull()
  })

  it('skipped_showsLabel', async () => {
    await mountTargets(overview({ monthTargets: [target({ contributedCents: 0, skipped: true })] }))
    expect(screen.getByText('Pulado este mês')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Registrar|Pular/ })).toBeNull()
  })

  it('noSalaryThisMonth_saysSo', async () => {
    const month = currentMonth()
    await mountTargets(overview({ month, salaryCents: 0, monthTargets: [] }))
    expect(norm(screen.getByText(/^Nenhum salário em/).textContent)).toBe(
      `Nenhum salário em ${formatMonth(month, 'pt-BR')} na categoria Salário.`,
    )
  })

  it('noSalaryThisMonth_futureMonthOrNoPercent_staysQuiet', async () => {
    await mountTargets(
      overview({ month: addMonths(currentMonth(), 1), salaryCents: 0, monthTargets: [] }),
    )
    expect(screen.queryByText(/^Nenhum salário em/)).toBeNull()
  })

  it('noTargets_rendersNothing', async () => {
    await mountTargets(overview({ monthTargets: [] }))
    expect(screen.queryByRole('list', { name: 'Metas do mês' })).toBeNull()
  })
})
