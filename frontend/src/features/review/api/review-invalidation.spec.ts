import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h } from 'vue'
import { useUndoImport } from '@/features/imports/api/imports-api'
import { useCreateRecurringRule } from '@/features/recurring/api/recurring-api'
import { useUpdateCategory } from '@/shared/api/queries/categories'
import { useCreateTransaction } from '@/shared/api/queries/transactions'
import { render } from '@/test/render'

const api = `${window.location.origin}/api/v1`

/** Mounts a component that only holds the mutation, runs it, and lists the query keys it invalidated. */
async function invalidatedBy(
  use: () => { mutateAsync: (input: never) => Promise<unknown> },
  input: unknown,
) {
  let mutation: ReturnType<typeof use> | undefined
  const Probe = defineComponent({
    setup() {
      mutation = use()
      return () => h('p')
    },
  })
  const ok = () => HttpResponse.json({})
  const { queryClient } = await render(
    Probe,
    {
      session: {},
      handlers: [
        http.post(`${api}/transactions`, ok),
        http.post(`${api}/recurring-rules`, ok),
        http.put(`${api}/categories/:id`, ok),
        http.patch(`${api}/categories/:id`, ok),
        http.delete(`${api}/imports/:id`, () => new HttpResponse(null, { status: 204 })),
        http.post(`${api}/imports/:id/undo`, ok),
      ],
    },
    {},
  )
  const spy = vi.spyOn(queryClient, 'invalidateQueries')
  await mutation!.mutateAsync(input as never)
  return spy.mock.calls.map(([f]) => f)
}

/** Every change to what was spent, or to how a category reads, has to reach the month review too. */
describe('month review refreshes', () => {
  it('afterTransactionCreated', async () => {
    const keys = await invalidatedBy(useCreateTransaction, {
      kind: 'EXPENSE',
      amountCents: 100,
      date: '2026-10-10',
      categoryId: 'c1',
    })
    expect(keys).toContainEqual({ queryKey: ['month-review'] })
  })

  it('afterRecurringRuleCreated', async () => {
    const keys = await invalidatedBy(useCreateRecurringRule, {
      kind: 'EXPENSE',
      amountCents: 100,
      categoryId: 'c1',
      frequency: 'MONTHLY',
      startDate: '2026-10-01',
    })
    expect(keys).toContainEqual({ queryKey: ['month-review'] })
  })

  it('afterCategoryUpdated', async () => {
    const keys = await invalidatedBy(useUpdateCategory, {
      id: 'c1',
      body: { name: 'Mercado', color: '#000000', icon: 'tag', kind: 'EXPENSE' },
    })
    expect(keys).toContainEqual({ queryKey: ['month-review'] })
  })

  it('afterImportUndone', async () => {
    const keys = await invalidatedBy(useUndoImport, 'batch-1')
    expect(keys).toContainEqual({ queryKey: ['month-review'] })
  })
})
