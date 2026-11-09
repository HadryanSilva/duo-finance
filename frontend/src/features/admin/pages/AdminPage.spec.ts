import { screen, waitFor, within } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { renderRoute } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const admin = { role: 'ADMIN' as const }

const entry = (id: string, email: string, status: string) => ({
  id,
  email,
  locale: 'pt-BR',
  status,
  createdAt: '2026-11-01T10:00:00Z',
  decidedAt: status === 'PENDING' ? null : '2026-11-02T10:00:00Z',
})
const page = (content: unknown[], p = 0, totalPages = 1) => ({
  content,
  page: p,
  size: 20,
  totalElements: content.length,
  totalPages,
})
const code = (over: Record<string, unknown> = {}) => ({
  id: 'c1',
  email: null,
  maxUses: 1,
  usedCount: 0,
  expiresAt: null,
  revokedAt: null,
  note: null,
  createdAt: '2026-11-01T10:00:00Z',
  ...over,
})

function waitlistHandler(rows: Record<string, unknown[]>) {
  return http.get(`${api}/admin/waitlist`, ({ request }) => {
    const url = new URL(request.url)
    return HttpResponse.json(page(rows[url.searchParams.get('status') ?? ''] ?? []))
  })
}

describe('AdminPage waitlist', () => {
  it('waitlist_approveAndReject', async () => {
    const posted: string[] = []
    const rows = { PENDING: [entry('w1', 'novo@example.com', 'PENDING')] }
    const { user } = await renderRoute({
      route: '/admin',
      session: admin,
      handlers: [
        waitlistHandler(rows),
        http.post(`${api}/admin/waitlist/:id/:action`, ({ params }) => {
          posted.push(`${params.id}/${params.action}`)
          return HttpResponse.json(entry('w1', 'novo@example.com', 'APPROVED'))
        }),
      ],
    })
    const row = (await screen.findByText('novo@example.com')).closest('li')!
    await user.click(within(row).getByRole('button', { name: 'Aprovar' }))
    expect(await screen.findByText(/convite enviado/i)).toBeTruthy()
    await user.click(within(row).getByRole('button', { name: 'Rejeitar' }))
    await waitFor(() => expect(posted).toEqual(['w1/approve', 'w1/reject']))
    // a PENDING entry is rejected without a confirmation
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('waitlist_approvedRowOffersResend', async () => {
    const posted: string[] = []
    const { user } = await renderRoute({
      route: '/admin?status=APPROVED',
      session: admin,
      handlers: [
        waitlistHandler({ APPROVED: [entry('w2', 'ok@example.com', 'APPROVED')] }),
        http.post(`${api}/admin/waitlist/:id/:action`, ({ params }) => {
          posted.push(`${params.id}/${params.action}`)
          return HttpResponse.json(entry('w2', 'ok@example.com', 'APPROVED'))
        }),
      ],
    })
    const row = (await screen.findByText('ok@example.com')).closest('li')!
    expect(within(row).queryByRole('button', { name: 'Aprovar' })).toBeNull()
    await user.click(within(row).getByRole('button', { name: 'Reenviar código' }))
    await waitFor(() => expect(posted).toEqual(['w2/approve']))
    expect(await screen.findByText(/novo código enviado/i)).toBeTruthy()

    // rejecting an approved entry warns that the emailed code is revoked first
    await user.click(within(row).getByRole('button', { name: 'Rejeitar' }))
    const dialog = await screen.findByRole('dialog')
    expect(within(dialog).getByText(/será revogado/i)).toBeTruthy()
    expect(posted).toEqual(['w2/approve'])
    await user.click(within(dialog).getByRole('button', { name: 'Rejeitar' }))
    await waitFor(() => expect(posted).toEqual(['w2/approve', 'w2/reject']))
  })

  it('filters by status and pages through the URL', async () => {
    const seen: URL[] = []
    const { user, router } = await renderRoute({
      route: '/admin',
      session: admin,
      handlers: [
        http.get(`${api}/admin/waitlist`, ({ request }) => {
          const url = new URL(request.url)
          seen.push(url)
          const p = Number(url.searchParams.get('page'))
          return HttpResponse.json(page([entry(`w${p}`, `p${p}@example.com`, 'PENDING')], p, 3))
        }),
      ],
    })
    expect(await screen.findByText('p0@example.com')).toBeTruthy()
    expect(
      seen.some(
        (u) => u.searchParams.get('status') === 'PENDING' && u.searchParams.get('size') === '20',
      ),
    ).toBe(true)
    expect(screen.getByRole('button', { name: 'Anterior' }).hasAttribute('disabled')).toBe(true)
    await user.click(screen.getByRole('button', { name: 'Próxima' }))
    expect(await screen.findByText('p1@example.com')).toBeTruthy()
    expect(router.currentRoute.value.query.page).toBe('2')
    await user.selectOptions(screen.getByLabelText('Status'), 'REJECTED')
    await waitFor(() => expect(router.currentRoute.value.query.status).toBe('REJECTED'))
    expect(router.currentRoute.value.query.page).toBeUndefined()
  })
})

describe('AdminPage access codes', () => {
  const codesRoute = '/admin?tab=codes'

  it('lists codes with derived status and revokes an active one after confirming', async () => {
    let revoked = ''
    const { user } = await renderRoute({
      route: codesRoute,
      session: admin,
      handlers: [
        http.get(`${api}/admin/access-codes`, () =>
          HttpResponse.json([
            code({ id: 'a1', note: 'Beta amigos', email: 'ana@example.com', usedCount: 0 }),
            code({ id: 'a2', note: 'Cheio', usedCount: 1 }),
            code({ id: 'a3', note: 'Velho', expiresAt: '2020-01-01T00:00:00Z' }),
            code({ id: 'a4', note: 'Cancelado', revokedAt: '2026-11-02T00:00:00Z' }),
          ]),
        ),
        http.post(`${api}/admin/access-codes/:id/revoke`, ({ params }) => {
          revoked = String(params.id)
          return new HttpResponse(null, { status: 204 })
        }),
      ],
    })
    const li = (text: string) => screen.getByText(text).closest('li')!
    await screen.findByText('Beta amigos')
    expect(within(li('Beta amigos')).getByText('Ativo')).toBeTruthy()
    expect(within(li('Beta amigos')).getByText('0/1')).toBeTruthy()
    expect(within(li('Cheio')).getByText('Esgotado')).toBeTruthy()
    expect(within(li('Velho')).getByText('Expirado')).toBeTruthy()
    expect(within(li('Cancelado')).getByText('Revogado')).toBeTruthy()
    expect(within(li('Cheio')).queryByRole('button', { name: 'Revogar' })).toBeNull()

    await user.click(within(li('Beta amigos')).getByRole('button', { name: 'Revogar' }))
    const dialog = await screen.findByRole('dialog')
    await user.click(within(dialog).getByRole('button', { name: 'Revogar' }))
    await waitFor(() => expect(revoked).toBe('a1'))
  })

  it('createCode_showsPlainCodeOnceThenForgets', async () => {
    let body: Record<string, unknown> = {}
    let listed = 0
    const { user, queryClient } = await renderRoute({
      route: codesRoute,
      session: admin,
      handlers: [
        http.get(`${api}/admin/access-codes`, () => {
          listed++
          return HttpResponse.json([])
        }),
        http.post(`${api}/admin/access-codes`, async ({ request }) => {
          body = (await request.json()) as Record<string, unknown>
          return HttpResponse.json({ code: code(), plainCode: 'DUO-SECRET-9999' }, { status: 201 })
        }),
      ],
    })
    await user.click(await screen.findByRole('button', { name: 'Gerar código' }))
    const panel = await screen.findByRole('dialog')
    await user.type(within(panel).getByLabelText('Nota (opcional)'), '  Para Bia ')
    await user.click(within(panel).getByRole('button', { name: 'Gerar' }))

    expect(await screen.findByText('DUO-SECRET-9999')).toBeTruthy()
    expect(body).toEqual({ maxUses: 1, note: 'Para Bia' })
    await waitFor(() => expect(listed).toBeGreaterThanOrEqual(2)) // list refreshed

    await user.click(screen.getByRole('button', { name: 'Fechar' }))
    await waitFor(() => expect(screen.queryByText('DUO-SECRET-9999')).toBeNull())
    expect(document.body.innerHTML).not.toContain('DUO-SECRET-9999')
    // not retained by the mutation cache either
    await waitFor(() => {
      const states = queryClient
        .getMutationCache()
        .getAll()
        .map((m) => m.state)
      expect(JSON.stringify(states)).not.toContain('DUO-SECRET-9999')
    })
  })

  it('createCode_validatesMaxUsesRange', async () => {
    let posts = 0
    const { user } = await renderRoute({
      route: codesRoute,
      session: admin,
      handlers: [
        http.get(`${api}/admin/access-codes`, () => HttpResponse.json([])),
        http.post(`${api}/admin/access-codes`, () => {
          posts++
          return HttpResponse.json({ code: code(), plainCode: 'X' }, { status: 201 })
        }),
      ],
    })
    await user.click(await screen.findByRole('button', { name: 'Gerar código' }))
    const panel = await screen.findByRole('dialog')
    const uses = within(panel).getByLabelText('Usos')
    for (const bad of ['0', '1001', '1.5', '']) {
      await user.clear(uses)
      if (bad) await user.type(uses, bad)
      await user.click(within(panel).getByRole('button', { name: 'Gerar' }))
      console.log('TXT', panel.textContent)
      await user.click(within(panel).getByRole('button', { name: 'Gerar' }))
      expect(await within(panel).findByText(/entre 1 e 1000/i)).toBeTruthy()
    }
    expect(posts).toBe(0)
    await user.clear(uses)
    await user.type(uses, '1000')
    await user.click(within(panel).getByRole('button', { name: 'Gerar' }))
    await waitFor(() => expect(posts).toBe(1))
  })

  it('validates the optional email', async () => {
    const { user } = await renderRoute({
      route: codesRoute,
      session: admin,
      handlers: [http.get(`${api}/admin/access-codes`, () => HttpResponse.json([]))],
    })
    await user.click(await screen.findByRole('button', { name: 'Gerar código' }))
    const panel = await screen.findByRole('dialog')
    await user.type(within(panel).getByLabelText('E-mail (opcional)'), 'nope')
    await user.click(within(panel).getByRole('button', { name: 'Gerar' }))
    expect(await within(panel).findByText(/e-mail válido/i)).toBeTruthy()
  })
})

describe('AdminPage access', () => {
  it('shows an error when the API answers 403', async () => {
    await renderRoute({
      route: '/admin',
      session: admin,
      handlers: [
        http.get(`${api}/admin/waitlist`, () =>
          HttpResponse.json({ status: 403, code: 'FORBIDDEN', detail: 'raw' }, { status: 403 }),
        ),
      ],
    })
    expect(await screen.findByRole('alert')).toBeTruthy()
  })
})
