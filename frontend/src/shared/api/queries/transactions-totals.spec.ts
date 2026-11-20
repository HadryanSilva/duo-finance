import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { render } from '@/test/render'
import { useTransactionTotals } from './transactions'

const api = `${window.location.origin}/api/v1`

const Probe = defineComponent({
  setup() {
    const totals = useTransactionTotals({ from: '2026-11-01', to: '2026-11-30', categoryId: 'c1' })
    return () => h('p', totals.data.value ? String(totals.data.value.count) : '')
  },
})

describe('useTransactionTotals', () => {
  it('totals_requestsWithFilterAndRefetchesOnInvalidate', async () => {
    const seen: string[] = []
    const { wrapper, queryClient } = await render(
      Probe,
      {
        session: {},
        handlers: [
          http.get(`${api}/transactions/totals`, ({ request }) => {
            seen.push(new URL(request.url).search)
            return HttpResponse.json({
              incomeCents: 0,
              expenseCents: 98000,
              netCents: -98000,
              count: 14,
            })
          }),
        ],
      },
      {},
    )
    await vi.waitFor(() => expect(wrapper.text()).toBe('14'))
    expect(seen[0]).toBe('?from=2026-11-01&to=2026-11-30&categoryId=c1')

    await queryClient.invalidateQueries({ queryKey: ['transactions'] })
    await vi.waitFor(() => expect(seen).toHaveLength(2))
  })
})
