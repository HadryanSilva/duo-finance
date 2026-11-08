import { screen } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { renderRoute } from '@/test/render'

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
