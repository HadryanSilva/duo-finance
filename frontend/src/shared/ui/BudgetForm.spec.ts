import { screen } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { BudgetResponse } from '@/shared/api/types'
import { render } from '@/test/render'
import BudgetForm from './BudgetForm.vue'

const api = `${window.location.origin}/api/v1`
const norm = (s: string | null | undefined) => (s ?? '').replace(/\s/g, ' ')

const mercado: BudgetResponse = {
  id: 'b-mercado',
  category: {
    id: 'c-mercado',
    name: 'Mercado',
    color: '#0ea5e9',
    icon: 'shopping-cart',
    archived: false,
  },
  monthlyLimitCents: 45000,
  month: '2026-10',
  spentCents: 52000,
  percent: 115,
  status: 'EXCEEDED',
} as BudgetResponse

function handlers(bodies: unknown[]) {
  return [
    http.get(`${api}/categories`, () => HttpResponse.json([])),
    http.post(`${api}/budgets`, async ({ request }) => {
      bodies.push(await request.json())
      return HttpResponse.json(mercado, { status: 201 })
    }),
    http.put(`${api}/budgets/:id`, async ({ request, params }) => {
      bodies.push({ id: params.id, ...((await request.json()) as object) })
      return HttpResponse.json(mercado)
    }),
  ]
}

const limitInput = () => screen.getByLabelText('Limite mensal') as HTMLInputElement

describe('BudgetForm', () => {
  it('budgetForm_fixedCategoryStartsOnSuggestion', async () => {
    const bodies: unknown[] = []
    const r = await render(
      BudgetForm,
      { session: {}, handlers: handlers(bodies) },
      {
        props: {
          category: { id: 'c-rest', name: 'Restaurantes' },
          suggestion: { cents: 12000, label: 'média dos últimos 3 meses' },
        },
      },
    )

    expect(screen.getByText('Restaurantes')).toBeTruthy()
    expect(screen.queryByRole('combobox')).toBeNull()
    expect(norm(limitInput().value)).toBe('€ 120,00')
    expect(screen.getByText('média dos últimos 3 meses')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Usar sugestão/ })).toBeNull()

    await r.user.click(screen.getByRole('button', { name: 'Salvar' }))
    await expect.poll(() => bodies).toEqual([{ categoryId: 'c-rest', monthlyLimitCents: 12000 }])
    expect(r.wrapper.emitted('done')).toHaveLength(1)
  })

  it('budgetForm_editKeepsLimitAndOffersSuggestion', async () => {
    const bodies: unknown[] = []
    const r = await render(
      BudgetForm,
      { session: {}, handlers: handlers(bodies) },
      {
        props: {
          budget: mercado,
          category: { id: 'c-mercado', name: 'Mercado' },
          suggestion: { cents: 43000, label: 'média dos últimos 3 meses' },
        },
      },
    )

    expect(norm(limitInput().value)).toBe('€ 450,00')
    await r.user.click(screen.getByRole('button', { name: /Usar sugestão: €\s430,00/ }))
    expect(norm(limitInput().value)).toBe('€ 430,00')
    expect(screen.queryByRole('button', { name: /Usar sugestão/ })).toBeNull()

    await r.user.click(screen.getByRole('button', { name: 'Salvar' }))
    await expect.poll(() => bodies).toEqual([{ id: 'b-mercado', monthlyLimitCents: 43000 }])
  })

  it('budgetForm_fixedCategoryShowsCategoryErrors', async () => {
    // The partner created this budget meanwhile: with no category select, the error must still show.
    const r = await render(
      BudgetForm,
      {
        session: {},
        handlers: [
          http.get(`${api}/categories`, () => HttpResponse.json([])),
          http.post(`${api}/budgets`, () =>
            HttpResponse.json({ code: 'BUDGET_EXISTS', status: 409 }, { status: 409 }),
          ),
        ],
      },
      {
        props: {
          category: { id: 'c-rest', name: 'Restaurantes' },
          suggestion: { cents: 12000, label: 'média dos últimos 3 meses' },
        },
      },
    )

    await r.user.click(screen.getByRole('button', { name: 'Salvar' }))
    expect((await screen.findByRole('alert')).textContent).toContain(
      'Já existe um orçamento para esta categoria.',
    )
    expect(r.wrapper.emitted('done')).toBeUndefined()
  })

  it('budgetForm_withoutNewPropsUnchanged', async () => {
    await render(BudgetForm, { session: {}, handlers: handlers([]) })
    expect(screen.getByRole('combobox')).toBeTruthy()
    expect(screen.queryByRole('button', { name: /Usar sugestão/ })).toBeNull()
  })
})
