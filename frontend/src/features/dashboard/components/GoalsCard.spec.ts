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
function mountCard(data: DashboardResponse, month = '2026-11') {
  return render(GoalsCard, { session: SESSION }, { props: { data, month } })
}

describe('GoalsCard', () => {
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

  const twoPending = () =>
    withGoals({
      month: currentMonth(),
      goals: [goal(), goal({ id: 'g2', name: 'Viagem' })],
      suggestions: [
        {
          goalId: 'g1',
          goalName: 'Videogame novo',
          month: currentMonth(),
          monthlyPercent: 5,
          suggestedCents: 15000,
        },
        {
          goalId: 'g2',
          goalName: 'Viagem',
          month: currentMonth(),
          monthlyPercent: 5,
          suggestedCents: 15000,
        },
      ],
    })

  it('pending_currentMonth_linksToGoals', async () => {
    await mountCard(twoPending(), currentMonth())
    const link = screen.getByRole('link', {
      name: `2 contribuições para confirmar em ${formatMonth(currentMonth(), 'pt-BR')}`,
    })
    expect(link.getAttribute('href')).toBe('/goals')
  })

  it('pending_pastMonth_isHidden', async () => {
    await mountCard(twoPending(), addMonths(currentMonth(), -1))
    expect(screen.queryByRole('link', { name: /para confirmar/ })).toBeNull()
  })

  it('noConfirmButtonsOnDashboard', async () => {
    await mountCard(twoPending(), currentMonth())
    expect(screen.queryByRole('button', { name: /Confirmar/ })).toBeNull()
    expect(screen.queryByRole('list', { name: 'Sugestões do mês' })).toBeNull()
  })
})
