import { screen, waitFor, within } from '@testing-library/dom'
import { http, HttpResponse, type RequestHandler } from 'msw'
import { describe, expect, it } from 'vitest'
import App from '@/app/App.vue'
import { render, renderRoute, testUser } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, detail: 'raw' }, { status })
const norm = (s: string | null) => (s ?? '').replace(/\s/g, ' ')

const ANA = '00000000-0000-4000-8000-000000000001'
const BIA = '00000000-0000-4000-8000-000000000002'
const account = {
  id: testUser.accountId,
  name: 'Ana e Bia',
  currency: 'EUR',
  members: [
    { userId: ANA, displayName: 'Ana Souza', email: 'a@x.com', joinedAt: '2026-01-01T00:00:00Z' },
    { userId: BIA, displayName: 'Bia Lima', email: 'b@x.com', joinedAt: '2026-01-02T00:00:00Z' },
  ],
  pendingInvitation: null,
}
const base = { initialBalanceCents: 0, createdAt: '', updatedAt: '' }
const nubank = {
  ...base,
  id: 'b1',
  name: 'Nubank',
  type: 'CHECKING',
  balanceCents: 123456,
  holderUserId: ANA,
  archived: false,
}
const cofre = {
  ...base,
  id: 'b2',
  name: 'Cofre',
  type: 'SAVINGS',
  balanceCents: 50000,
  holderUserId: null,
  archived: false,
}
const antiga = { ...cofre, id: 'b3', name: 'Antiga', balanceCents: 999900, archived: true }

function open(route: string, extra: RequestHandler[] = []) {
  const calls: Record<string, unknown> = {}
  return {
    calls,
    // renderRoute waits for the page heading, which an open side panel hides from the a11y tree
    mount: () => render(App, { route, session: {}, handlers: handlers() }),
    render: () => renderRoute({ route, session: {}, handlers: handlers() }),
  }
  function handlers() {
    return [
      ...extra,
      http.get(`${api}/account`, () => HttpResponse.json(account)),
      http.get(`${api}/bank-accounts`, ({ request }) => {
        const all = new URL(request.url).searchParams.get('includeArchived') === 'true'
        return HttpResponse.json(all ? [nubank, cofre, antiga] : [nubank, cofre])
      }),
      http.put(`${api}/bank-accounts/b1`, async ({ request }) => {
        calls.put = await request.json()
        return HttpResponse.json(nubank)
      }),
      http.post(`${api}/bank-accounts/:id/archive`, ({ params }) => {
        calls.archived = params.id
        return HttpResponse.json(nubank)
      }),
    ]
  }
}

describe('BankAccountsPage', () => {
  it('bankAccounts_listsWithBalancesAndTotal', async () => {
    await open('/bank-accounts').render()
    await screen.findByText('Nubank')
    expect(norm(document.body.textContent)).toContain('€ 1.234,56')
    expect(screen.getByText('Conta corrente')).toBeTruthy()
    expect(screen.getByText('Titular: Ana Souza')).toBeTruthy()
    expect(norm(screen.getByTestId('bank-total').textContent)).toContain('€ 1.734,56')
  })

  it('bankAccounts_archivedToggle_showsArchivedButExcludesFromTotal', async () => {
    const { user, router } = await open('/bank-accounts').render()
    await screen.findByText('Nubank')
    await user.click(screen.getByRole('switch', { name: 'Mostrar arquivadas' }))
    await screen.findByText('Antiga')
    expect(screen.getByText('Arquivada')).toBeTruthy()
    expect(router.currentRoute.value.query.archived).toBe('1')
    expect(norm(screen.getByTestId('bank-total').textContent)).toContain('€ 1.734,56')
  })

  it('bankAccounts_newQuery_opensCreatePanel', async () => {
    await open('/bank-accounts?new=1').mount()
    expect(await screen.findByRole('dialog', { name: 'Nova conta' })).toBeTruthy()
  })

  it('bankAccounts_deleteInUse_offersArchive', async () => {
    const s = open('/bank-accounts', [
      http.delete(`${api}/bank-accounts/b1`, () => problem(409, 'BANK_ACCOUNT_IN_USE')),
    ])
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Excluir Nubank' }))
    const confirm = await screen.findByRole('dialog', { name: 'Excluir Nubank?' })
    await user.click(within(confirm).getByRole('button', { name: 'Excluir' }))
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('em vez de excluir')
    await user.click(within(alert).getByRole('button', { name: 'Arquivar' }))
    await waitFor(() => expect(s.calls.archived).toBe('b1'))
  })

  it('bankAccounts_archiveUsedByRule_showsLinkToRecurring', async () => {
    const s = open('/bank-accounts', [
      http.post(`${api}/bank-accounts/b1/archive`, () => problem(409, 'IN_USE_BY_ACTIVE_RULE')),
    ])
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Arquivar Nubank' }))
    const alert = await screen.findByRole('alert')
    expect(alert.textContent).toContain('recorrente ativo')
    expect(within(alert).getByRole('link').getAttribute('href')).toBe('/recurring')
  })

  it('bankAccountForm_sendsCentsAndKeepsHolder', async () => {
    const s = open('/bank-accounts')
    const { user } = await s.render()
    await user.click(await screen.findByRole('button', { name: 'Editar Nubank' }))
    const dialog = await screen.findByRole('dialog', { name: 'Editar conta' })
    await within(dialog).findByRole('option', { name: 'Bia Lima' })
    const balance = within(dialog).getByLabelText('Saldo inicial')
    await user.click(balance)
    await user.keyboard('{Control>}a{/Control}10,50')
    await user.click(within(dialog).getByRole('button', { name: 'Salvar' }))
    await waitFor(() =>
      expect(s.calls.put).toEqual({
        name: 'Nubank',
        type: 'CHECKING',
        initialBalanceCents: 1050,
        holderUserId: ANA,
      }),
    )
  })
})
