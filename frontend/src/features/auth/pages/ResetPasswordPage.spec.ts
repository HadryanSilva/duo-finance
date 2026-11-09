import { screen, waitFor } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { renderRoute } from '@/test/render'

const api = `${window.location.origin}/api/v1`

describe('ResetPasswordPage', () => {
  it('resetPassword_success_goesToLoginWithNotice', async () => {
    let body: unknown
    const r = await renderRoute({
      route: '/reset-password?token=tok',
      handlers: [
        http.post(`${api}/auth/reset-password`, async ({ request }) => {
          body = await request.json()
          return new HttpResponse(null, { status: 204 })
        }),
      ],
    })
    await r.user.type(screen.getByLabelText(/^Nova senha/), 'new-secret-1')
    await r.user.click(screen.getByRole('button', { name: 'Redefinir senha' }))
    await waitFor(() => expect(r.router.currentRoute.value.path).toBe('/login'))
    expect(r.router.currentRoute.value.query.reset).toBe('1')
    expect(body).toEqual({ token: 'tok', newPassword: 'new-secret-1' })
  })

  it('resetPassword_invalidToken_showsError', async () => {
    const r = await renderRoute({
      route: '/reset-password?token=bad',
      handlers: [
        http.post(`${api}/auth/reset-password`, () =>
          HttpResponse.json({ status: 400, code: 'TOKEN_INVALID' }, { status: 400 }),
        ),
      ],
    })
    await r.user.type(screen.getByLabelText(/^Nova senha/), 'new-secret-1')
    await r.user.click(screen.getByRole('button', { name: 'Redefinir senha' }))
    expect((await screen.findByRole('alert')).textContent).toContain('inválido ou expirou')
  })

  it('resetPassword_shortPassword_isRejectedBeforeSending', async () => {
    const r = await renderRoute({ route: '/reset-password?token=tok' })
    await r.user.type(screen.getByLabelText(/^Nova senha/), 'short')
    await r.user.click(screen.getByRole('button', { name: 'Redefinir senha' }))
    expect(await screen.findByText(/tamanho/)).toBeTruthy()
  })
})
