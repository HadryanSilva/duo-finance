import { screen, waitFor } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { AuthResponse, InvitationPreviewResponse } from '@/shared/api/types'
import { useSession } from '@/shared/session/session-store'
import { renderRoute, testUser } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const preview = (over: Partial<InvitationPreviewResponse> = {}) =>
  http.get(`${api}/account/invitations/tok`, () =>
    HttpResponse.json({
      inviterName: 'Bia Lima',
      accountName: 'Casa da Bia',
      email: 'ana@example.com',
      existingUser: true,
      ...over,
    }),
  )
const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, detail: 'raw' }, { status })
const switched: AuthResponse = {
  accessToken: 'switched-token',
  expiresIn: 900,
  user: { ...testUser, accountId: '00000000-0000-4000-8000-0000000000b2' },
}

describe('AcceptInvitePage', () => {
  it('acceptInvite_showsPreview', async () => {
    await renderRoute({ route: '/invite/tok', handlers: [preview()] })
    expect(await screen.findByText(/Bia Lima/)).toBeTruthy()
    expect(screen.getByText(/Casa da Bia/)).toBeTruthy()
    expect(screen.getByText(/ana@example.com/)).toBeTruthy()
  })

  it('acceptInvite_expired_showsMessage', async () => {
    await renderRoute({
      route: '/invite/tok',
      handlers: [http.get(`${api}/account/invitations/tok`, () => problem(410, 'INVITE_EXPIRED'))],
    })
    expect((await screen.findByRole('alert')).textContent).toContain('expirou')
    expect(document.body.textContent).not.toContain('raw')
  })

  it('acceptInvite_anonymous_offersLoginOrRegister', async () => {
    await renderRoute({ route: '/invite/tok', handlers: [preview()] })
    const login = await screen.findByRole('link', { name: 'Entrar' })
    const url = new URL(login.getAttribute('href') ?? '', window.location.origin)
    expect(url.pathname).toBe('/login')
    expect(url.searchParams.get('redirect')).toBe('/invite/tok')
    expect(screen.queryByRole('button', { name: 'Aceitar convite' })).toBeNull()
  })

  it('acceptInvite_anonymousNewUser_offersRegister', async () => {
    await renderRoute({
      route: '/invite/tok',
      handlers: [preview({ existingUser: false })],
    })
    const reg = await screen.findByRole('link', { name: 'Criar conta' })
    const url = new URL(reg.getAttribute('href') ?? '', window.location.origin)
    expect(url.pathname).toBe('/register')
    expect(url.searchParams.get('invite')).toBe('tok')
    expect(url.searchParams.get('email')).toBe('ana@example.com')
    expect(screen.queryByRole('link', { name: 'Entrar' })).toBeNull()
  })

  it('acceptInvite_authenticated_acceptsAndSwitchesAccount', async () => {
    const bodies: unknown[] = []
    const r = await renderRoute({
      route: '/invite/tok',
      session: {},
      handlers: [
        preview(),
        http.post(`${api}/account/invitations/tok/accept`, async ({ request }) => {
          bodies.push(await request.json())
          return HttpResponse.json(switched)
        }),
      ],
    })
    await r.user.click(await screen.findByRole('button', { name: 'Aceitar convite' }))
    await waitFor(() => expect(r.router.currentRoute.value.path).toBe('/dashboard'))
    expect(useSession(r.pinia).accessToken).toBe('switched-token')
    expect(bodies).toEqual([{ confirmDiscard: false }])
  })

  it('acceptInvite_withData_confirmsDiscardThenSwitchesAccount', async () => {
    const bodies: { confirmDiscard?: boolean }[] = []
    const r = await renderRoute({
      route: '/invite/tok',
      session: {},
      handlers: [
        preview(),
        http.post(`${api}/account/invitations/tok/accept`, async ({ request }) => {
          const body = (await request.json()) as { confirmDiscard?: boolean }
          bodies.push(body)
          return body.confirmDiscard
            ? HttpResponse.json(switched)
            : problem(409, 'ACCOUNT_HAS_DATA')
        }),
      ],
    })
    await r.user.click(await screen.findByRole('button', { name: 'Aceitar convite' }))
    const dialog = await screen.findByRole('dialog')
    expect(dialog.textContent).toContain('apagados')
    expect(r.router.currentRoute.value.path).toBe('/invite/tok')
    await r.user.click(screen.getByRole('button', { name: 'Aceitar e apagar meus dados' }))
    await waitFor(() => expect(r.router.currentRoute.value.path).toBe('/dashboard'))
    expect(bodies).toEqual([{ confirmDiscard: false }, { confirmDiscard: true }])
    expect(useSession(r.pinia).accessToken).toBe('switched-token')
  })

  it('acceptInvite_cancelDialog_keepsSessionAndSendsNothingElse', async () => {
    let posts = 0
    const r = await renderRoute({
      route: '/invite/tok',
      session: {},
      handlers: [
        preview(),
        http.post(`${api}/account/invitations/tok/accept`, () => {
          posts++
          return problem(409, 'ACCOUNT_HAS_DATA')
        }),
      ],
    })
    await r.user.click(await screen.findByRole('button', { name: 'Aceitar convite' }))
    await screen.findByRole('dialog')
    await r.user.click(screen.getByRole('button', { name: 'Cancelar' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(posts).toBe(1)
    expect(useSession(r.pinia).accessToken).toBe('test-access-token')
  })

  it('acceptInvite_otherEmail_showsNotForThisAccount', async () => {
    const r = await renderRoute({
      route: '/invite/tok',
      session: {},
      handlers: [
        preview(),
        http.post(`${api}/account/invitations/tok/accept`, () => problem(404, 'NOT_FOUND')),
      ],
    })
    await r.user.click(await screen.findByRole('button', { name: 'Aceitar convite' }))
    expect((await screen.findByRole('alert')).textContent).toContain('não é para esta conta')
  })
})
