import { screen } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { cleanup, renderRoute } from '@/test/render'

const api = `${window.location.origin}/api/v1`

describe('ForgotPasswordPage', () => {
  it('forgotPassword_alwaysShowsSameMessage', async () => {
    const seen: string[] = []
    for (const email of ['known@example.com', 'unknown@example.com']) {
      cleanup()
      const r = await renderRoute({
        route: '/forgot-password',
        handlers: [
          http.post(`${api}/auth/forgot-password`, () => new HttpResponse(null, { status: 202 })),
        ],
      })
      await r.user.type(screen.getByLabelText(/^E-mail/), email)
      await r.user.click(screen.getByRole('button', { name: 'Enviar link' }))
      seen.push((await screen.findByRole('status')).textContent ?? '')
    }
    expect(seen[0]).toBe(seen[1])
    expect(seen[0]).toContain('Verifique seu e-mail')
  })

  it('forgotPassword_invalidEmail_isRejectedBeforeSending', async () => {
    const r = await renderRoute({ route: '/forgot-password' })
    await r.user.type(screen.getByLabelText(/^E-mail/), 'nope')
    await r.user.click(screen.getByRole('button', { name: 'Enviar link' }))
    expect(await screen.findByText(/e-mail válido/)).toBeTruthy()
  })
})
