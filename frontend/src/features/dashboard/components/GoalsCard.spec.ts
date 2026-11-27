import { screen, within } from '@testing-library/dom'
import { describe, expect, it } from 'vitest'
import type { DashboardResponse, GoalResponse } from '@/shared/api/types'
import { addMonths, currentMonth, formatMonth } from '@/shared/lib/dates'
import { render } from '@/test/render'
import GoalsCard from './GoalsCard.vue'

const norm = (s: string | null) => (s ?? '').replace(/\s/g, ' ')

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
  revolutPocket: null,
  ...over,
})

const salary = { id: 'c2', name: 'Salário', archived: false, color: '#0000aa', icon: 'gift' }

const target = (goalId: string, goalName: string, month: string, over: object = {}) => ({
  goalId,
  goalName,
  month,
  monthlyPercent: 5,
  targetCents: 15000,
  contributedCents: 0,
  skipped: false,
  ...over,
})

const overview = (over: Partial<DashboardResponse['goals']> = {}) => ({
  goals: {
    month: '2026-11',
    salaryCategory: salary,
    salaryCents: 300000,
    totalSavedCents: 40000,
    goals: [goal()],
    completed: [],
    monthTargets: [target('g1', 'Videogame novo', '2026-11')],
    ...over,
  },
})
// Only `goals` is read by the card.
const withGoals = (over?: Partial<DashboardResponse['goals']>) =>
  overview(over) as unknown as DashboardResponse

const SESSION = {}
function mountCard(data: DashboardResponse, month = '2026-11') {
  return render(GoalsCard, { session: SESSION }, { props: { data, month } })
}

describe('GoalsCard', () => {
  it('noSalaryCategory_showsHint', async () => {
    await mountCard(withGoals({ salaryCategory: null, monthTargets: [] }))
    const hint = screen.getByRole('link', {
      name: 'Escolha a categoria do salário para receber sugestões',
    })
    expect(hint.getAttribute('href')).toBe('/goals')
  })

  it('archivedSalaryCategory_showsHint', async () => {
    await mountCard(withGoals({ salaryCategory: { ...salary, archived: true }, monthTargets: [] }))
    expect(
      screen.getByRole('link', { name: 'Escolha a categoria do salário para receber sugestões' }),
    ).toBeTruthy()
  })

  it('usableSalaryCategory_hidesHint', async () => {
    await mountCard(withGoals({ monthTargets: [] }))
    expect(screen.queryByText('Escolha a categoria do salário para receber sugestões')).toBeNull()
  })

  it('empty_offersCreate', async () => {
    await mountCard(withGoals({ goals: [], monthTargets: [] }))
    const cta = screen.getByRole('link', { name: 'Crie um objetivo' })
    expect(cta.getAttribute('href')).toBe('/goals?new=1')
  })

  it('progress_showsSavedOverTarget', async () => {
    await mountCard(withGoals({ monthTargets: [] }))
    const bar = screen.getByRole('progressbar', { name: 'Videogame novo' })
    expect(bar.getAttribute('aria-valuenow')).toBe('40')
    const item = bar.closest('li') as HTMLElement
    expect(norm(item.textContent)).toContain('€ 400,00')
    expect(norm(item.textContent)).toContain('€ 1.000,00')
    expect(within(item).getByText('40%')).toBeTruthy()
    const all = screen.getByRole('link', { name: 'Ver todos os objetivos' })
    expect(all.closest('[data-slot="card-action"]')).not.toBeNull()
    expect(all.getAttribute('href')).toBe('/goals')
  })

  it('reached_showsBadge', async () => {
    await mountCard(
      withGoals({ goals: [goal({ status: 'REACHED', savedCents: 100000 })], monthTargets: [] }),
    )
    const item = screen.getByRole('progressbar').closest('li') as HTMLElement
    expect(within(item).getByText('Atingido')).toBeTruthy()
  })

  const twoPending = () =>
    withGoals({
      month: currentMonth(),
      goals: [goal(), goal({ id: 'g2', name: 'Viagem' })],
      monthTargets: [
        target('g1', 'Videogame novo', currentMonth()),
        target('g2', 'Viagem', currentMonth()),
      ],
    })

  it('pending_currentMonth_linksToGoals', async () => {
    await mountCard(twoPending(), currentMonth())
    const link = screen.getByRole('link', {
      name: `2 metas do mês em aberto em ${formatMonth(currentMonth(), 'pt-BR')}`,
    })
    expect(link.getAttribute('href')).toBe('/goals')
  })

  it('pending_pastMonth_isHidden', async () => {
    await mountCard(twoPending(), addMonths(currentMonth(), -1))
    expect(screen.queryByRole('link', { name: /metas do mês em aberto/ })).toBeNull()
  })

  it('pending_ignoresMetAndSkippedTargets', async () => {
    const data = withGoals({
      month: currentMonth(),
      goals: [goal(), goal({ id: 'g2', name: 'Viagem' }), goal({ id: 'g3', name: 'Casa' })],
      monthTargets: [
        target('g1', 'Videogame novo', currentMonth(), { contributedCents: 15000 }),
        target('g2', 'Viagem', currentMonth(), { skipped: true }),
        target('g3', 'Casa', currentMonth()),
      ],
    })
    await mountCard(data, currentMonth())
    expect(
      screen.getByRole('link', {
        name: `1 meta do mês em aberto em ${formatMonth(currentMonth(), 'pt-BR')}`,
      }),
    ).toBeTruthy()
  })

  // Like the Goals page: a month without a target stays open until a contribution or a skip.
  it('pending_countsTargetlessMonthsUntilAContributionOrSkip', async () => {
    const none = { targetCents: 0 }
    const data = withGoals({
      month: currentMonth(),
      salaryCents: 0,
      goals: [goal(), goal({ id: 'g2', name: 'Viagem' }), goal({ id: 'g3', name: 'Casa' })],
      monthTargets: [
        target('g1', 'Videogame novo', currentMonth(), none),
        target('g2', 'Viagem', currentMonth(), { ...none, contributedCents: 100 }),
        target('g3', 'Casa', currentMonth(), { ...none, skipped: true }),
      ],
    })
    await mountCard(data, currentMonth())
    expect(
      screen.getByRole('link', {
        name: `1 meta do mês em aberto em ${formatMonth(currentMonth(), 'pt-BR')}`,
      }),
    ).toBeTruthy()
  })

  it('noConfirmButtonsOnDashboard', async () => {
    await mountCard(twoPending(), currentMonth())
    expect(screen.queryByRole('button', { name: /Registrar|Pular/ })).toBeNull()
    expect(screen.queryByRole('list', { name: 'Metas do mês' })).toBeNull()
  })
})
