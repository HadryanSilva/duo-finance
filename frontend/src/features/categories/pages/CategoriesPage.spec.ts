import { screen, waitFor, within } from '@testing-library/dom'
import { http, HttpResponse, type RequestHandler } from 'msw'
import { beforeEach, describe, expect, it } from 'vitest'
import { renderRoute, testUser } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, detail: 'raw' }, { status })

const account = {
  id: testUser.accountId,
  name: 'Ana',
  currency: 'EUR',
  members: [],
  pendingInvitation: null,
}
const mercado = {
  id: 'c1',
  name: 'Mercado',
  kind: 'EXPENSE',
  color: '#16A34A',
  icon: 'shopping-cart',
  systemDefault: true,
  archived: false,
}
const salario = { ...mercado, id: 'c2', name: 'Salário', kind: 'INCOME', icon: 'banknote' }

// styles.css is not loaded under jsdom: stand in for the category color tokens.
beforeEach(() => {
  for (let i = 1; i <= 12; i++) {
    document.documentElement.style.setProperty(
      `--color-cat-${i}`,
      `#0000${String(i).padStart(2, '0')}`,
    )
  }
})

function open(extra: RequestHandler[] = []) {
  const calls: Record<string, unknown> = {}
  return {
    calls,
    render: () =>
      renderRoute({
        route: '/categories',
        session: {},
        handlers: [
          ...extra,
          http.get(`${api}/account`, () => HttpResponse.json(account)),
          http.get(`${api}/categories`, ({ request }) => {
            const kind = new URL(request.url).searchParams.get('kind')
            return HttpResponse.json([mercado, salario].filter((c) => !kind || c.kind === kind))
          }),
          http.put(`${api}/categories/c1`, async ({ request }) => {
            calls.put = await request.json()
            return HttpResponse.json(mercado)
          }),
        ],
      }),
  }
}

describe('CategoriesPage', () => {
  it('categories_tabsByKind_andHidesKindOnEdit', async () => {
    const { user } = await open().render()
    await screen.findByText('Mercado')
    expect(screen.queryByText('Salário')).toBeNull()
    await user.click(screen.getByRole('tab', { name: 'Receitas' }))
    await screen.findByText('Salário')
    expect(screen.queryByText('Mercado')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Editar Salário' }))
    const dialog = await screen.findByRole('dialog', { name: 'Editar categoria' })
    expect(within(dialog).getByLabelText('Nome')).toBeTruthy()
    expect(within(dialog).queryByLabelText('Tipo')).toBeNull()
  })

  it('categories_edit_sendsNameColorIconWithoutKind', async () => {
    const s = open()
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Editar Mercado' }))
    const dialog = await screen.findByRole('dialog', { name: 'Editar categoria' })
    await user.click(within(dialog).getByRole('radio', { name: 'Cor 3' }))
    await user.click(within(dialog).getByRole('radio', { name: 'Carro' }))
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    await waitFor(() =>
      expect(s.calls.put).toEqual({ name: 'Mercado', color: '#000003', icon: 'car' }),
    )
  })

  it('categories_nameTaken_showsFieldError', async () => {
    const s = open([http.post(`${api}/categories`, () => problem(409, 'CATEGORY_NAME_TAKEN'))])
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Nova categoria' }))
    const dialog = await screen.findByRole('dialog', { name: 'Nova categoria' })
    await user.type(within(dialog).getByLabelText('Nome'), 'Mercado')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    const name = within(dialog).getByLabelText('Nome')
    await waitFor(() => expect(name.getAttribute('aria-invalid')).toBe('true'))
    expect(dialog.textContent).toContain('Já existe uma categoria com este nome')
  })
})
