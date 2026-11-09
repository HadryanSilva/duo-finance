import { screen } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { useSession } from '@/shared/session/session-store'
import { renderRoute, testUser } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, detail: 'raw' }, { status })

describe('VerifyEmailPage', () => {
  it('verifyEmail_validToken_showsSuccess', async () => {
    let body: unknown
    await renderRoute({
      route: '/verify-email?token=good',
      handlers: [
        http.post(`${api}/auth/verify-email`, async ({ request }) => {
          body = await request.json()
          return new HttpResponse(null, { status: 204 })
        }),
      ],
    })
    expect((await screen.findByRole('status')).textContent).toContain('E-mail confirmado')
    expect(body).toEqual({ token: 'good' })
  })

  it('verifyEmail_signedIn_onboardingShowsInviteFormWithoutReload', async () => {
    let verified = false
    const r = await renderRoute({
      route: '/verify-email?token=good',
      session: { emailVerified: false },
      onboarded: false,
      handlers: [
        http.post(`${api}/auth/verify-email`, () => {
          verified = true
          return new HttpResponse(null, { status: 204 })
        }),
        http.get(`${api}/me`, () => HttpResponse.json({ ...testUser, emailVerified: verified })),
        http.get(`${api}/account`, () =>
          HttpResponse.json({
            id: testUser.accountId,
            name: 'Ana',
            currency: 'EUR',
            members: [],
            pendingInvitation: null,
          }),
        ),
        http.patch(`${api}/account`, () => HttpResponse.json({})),
        http.get(`${api}/bank-accounts`, () => HttpResponse.json([])),
      ],
    })
    expect((await screen.findByRole('status')).textContent).toContain('E-mail confirmado')
    await vi.waitFor(() => expect(useSession(r.pinia).user?.emailVerified).toBe(true))

    await r.user.click(screen.getByRole('link', { name: 'Continuar' }))
    await vi.waitFor(() => expect(r.router.currentRoute.value.path).toBe('/onboarding'))
    await screen.findByLabelText('Nome do casal')
    await r.user.click(screen.getByRole('button', { name: 'Continuar' }))
    expect(await screen.findByLabelText('E-mail do parceiro')).toBeTruthy()
  })

  it('verifyEmail_invalidToken_showsError', async () => {
    await renderRoute({
      route: '/verify-email?token=bad',
      handlers: [http.post(`${api}/auth/verify-email`, () => problem(400, 'TOKEN_INVALID'))],
    })
    expect((await screen.findByRole('alert')).textContent).toContain('inválido ou expirou')
    expect(screen.queryByRole('button', { name: 'Reenviar e-mail' })).toBeNull()
  })

  it('verifyEmail_missingToken_showsErrorWithoutRequest', async () => {
    await renderRoute({ route: '/verify-email' })
    expect((await screen.findByRole('alert')).textContent).toContain('inválido ou expirou')
  })

  it('verifyEmail_authenticated_canResend', async () => {
    let resent = 0
    const r = await renderRoute({
      route: '/verify-email?token=bad',
      session: { emailVerified: false },
      handlers: [
        http.post(`${api}/auth/verify-email`, () => problem(400, 'TOKEN_INVALID')),
        http.post(`${api}/auth/resend-verification`, () => {
          resent++
          return new HttpResponse(null, { status: 202 })
        }),
      ],
    })
    await r.user.click(await screen.findByRole('button', { name: 'Reenviar e-mail' }))
    expect(await screen.findByRole('status')).toBeTruthy()
    expect(resent).toBe(1)
  })
})
