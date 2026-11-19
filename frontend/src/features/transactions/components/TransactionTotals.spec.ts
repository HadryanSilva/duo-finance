import { screen, waitFor } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { TransactionFilter } from '@/shared/api/queries/transactions'
import { render } from '@/test/render'
import TransactionTotals from './TransactionTotals.vue'

const api = `${window.location.origin}/api/v1`
const norm = (s: string | null | undefined) => (s ?? '').replace(/\s/g, ' ')

function mount(filter: TransactionFilter, reply: () => Response) {
  return render(
    TransactionTotals,
    { session: {}, handlers: [http.get(`${api}/transactions/totals`, reply)] },
    { props: { filter } },
  )
}
const totals = (incomeCents: number, expenseCents: number, count: number) => () =>
  HttpResponse.json({ incomeCents, expenseCents, netCents: incomeCents - expenseCents, count })

describe('TransactionTotals', () => {
  it('totals_showInOutAndNet', async () => {
    const { wrapper } = await mount({ categoryId: 'c1' }, totals(0, 98000, 14))
    await waitFor(() => expect(norm(wrapper.text())).toContain('14 lançamentos'))
    const text = norm(wrapper.text())
    expect(text).toContain('Entrou')
    expect(text).toContain('€ 980,00')
    expect(text).toContain('Saldo do período')
    expect(wrapper.find('[data-testid="totals-net"]').classes()).toContain('text-expense')
  })

  it('totals_transferKindWithoutAccount_showsOnlyCount', async () => {
    const { wrapper } = await mount({ kind: 'TRANSFER' }, totals(0, 0, 3))
    await waitFor(() => expect(norm(wrapper.text())).toContain('3 transferências'))
    expect(wrapper.text()).not.toContain('Entrou')
  })

  it('totals_withAccount_transferKind_showsAmounts', async () => {
    const { wrapper } = await mount({ kind: 'TRANSFER', bankAccountId: 'b1' }, totals(999, 0, 1))
    await waitFor(() => expect(wrapper.text()).toContain('Entrou'))
  })

  it('totals_error_showsAlert', async () => {
    await mount({}, () => HttpResponse.json({ status: 500, code: 'INTERNAL' }, { status: 500 }))
    expect(await screen.findByRole('alert')).toBeTruthy()
  })

  it('totals_zeroCount_rendersNothing', async () => {
    let answered = false
    const { wrapper } = await mount({}, () => {
      answered = true
      return totals(0, 0, 0)()
    })
    await waitFor(() => expect(answered).toBe(true))
    await waitFor(() => expect(wrapper.find('[data-slot="skeleton"]').exists()).toBe(false))
    expect(wrapper.text()).toBe('')
  })
})
