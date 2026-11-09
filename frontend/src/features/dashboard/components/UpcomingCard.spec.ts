import { screen, within } from '@testing-library/dom'
import { describe, expect, it } from 'vitest'
import type { DashboardResponse } from '@/shared/api/types'
import { render } from '@/test/render'
import UpcomingCard from './UpcomingCard.vue'

type Upcoming = DashboardResponse['upcoming'][number]
const rule = (over: Partial<Upcoming>): Upcoming => ({
  ruleId: 'r1',
  date: '2026-03-31',
  kind: 'EXPENSE',
  amountCents: 150000,
  description: 'Aluguel',
  categoryId: null,
  ...over,
})
const norm = (s: string | null) => (s ?? '').replace(/\s/g, ' ')
const withUpcoming = (upcoming: Upcoming[]) => ({ upcoming }) as DashboardResponse

describe('UpcomingCard', () => {
  it('upcoming_formatsBusinessDates', async () => {
    const data = withUpcoming([
      rule({}),
      rule({
        ruleId: 'r2',
        date: '2026-04-01',
        kind: 'INCOME',
        amountCents: 300000,
        description: null,
      }),
    ])
    await render(UpcomingCard, {}, { props: { data } })
    const list = screen.getByRole('list', { name: 'Próximos lançamentos' })
    const items = within(list).getAllByRole('listitem')
    // dd/MM/yyyy in pt-BR, never shifted by the time zone
    expect(norm(items[0]!.textContent)).toContain('31/03/2026')
    expect(norm(items[0]!.textContent)).toContain('Aluguel')
    expect(norm(items[0]!.textContent)).toContain('€ 1.500,00')
    expect(norm(items[1]!.textContent)).toContain('01/04/2026')
    // no description: falls back to the kind label
    expect(norm(items[1]!.textContent)).toContain('Receita')
    expect(norm(items[1]!.textContent)).toContain('+€ 3.000,00')
  })

  it('amounts_areColoredByKind', async () => {
    const data = withUpcoming([rule({}), rule({ ruleId: 'r2', kind: 'INCOME', amountCents: 100 })])
    await render(UpcomingCard, {}, { props: { data } })
    const items = screen.getAllByRole('listitem')
    expect(items[0]!.querySelector('.text-expense')).not.toBeNull()
    expect(items[1]!.querySelector('.text-income')).not.toBeNull()
  })

  it('empty_offersRecurringCta', async () => {
    await render(UpcomingCard, {}, { props: { data: withUpcoming([]) } })
    const cta = screen.getByRole('link', { name: 'Criar recorrência' })
    expect(cta.getAttribute('href')).toBe('/recurring')
  })
})
