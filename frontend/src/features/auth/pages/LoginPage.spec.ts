import { screen, waitFor } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import type { AuthResponse } from '@/shared/api/types'
import { markOnboarded } from '@/shared/session/onboarding'
import { useSession } from '@/shared/session/session-store'
import { renderRoute, testUser } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const authResponse: AuthResponse = { accessToken: 'new-token', expiresIn: 900, user: testUser }

async function fillAndSubmit(r: Awaited<ReturnType<typeof renderRoute>>) {
  await r.user.type(screen.getByLabelText(/^E-mail/), 'ana@example.com')
  await r.user.type(screen.getByLabelText(/^Senha/), 'secret123')
  await r.user.click(screen.getByRole('button', { name: 'Entrar' }))
}

describe('LoginPage', () => {
  it('login_success_redirectsToRedirectParam', async () => {
    markOnboarded(testUser.id)
    let body: unknown
    const r = await renderRoute({
      route: '/login?redirect=/transactions?x=1',
      handlers: [
        http.post(`${api}/auth/login`, async ({ request }) => {
          body = await request.json()
          return HttpResponse.json(authResponse)
        }),
      ],
    })
    await fillAndSubmit(r)
    await waitFor(() => expect(r.router.currentRoute.value.fullPath).toBe('/transactions?x=1'))
    expect(body).toEqual({ email: 'ana@example.com', password: 'secret123' })
    expect(useSession(r.pinia).accessToken).toBe('new-token')
  })

  it('login_openRedirect_fallsBackToDashboard', async () => {
    markOnboarded(testUser.id)
    const r = await renderRoute({
      route: '/login?redirect=//evil.com',
      handlers: [http.post(`${api}/auth/login`, () => HttpResponse.json(authResponse))],
    })
    await fillAndSubmit(r)
    await waitFor(() => expect(r.router.currentRoute.value.fullPath).toBe('/dashboard'))
  })

  async function failWith(res: () => Response) {
    const r = await renderRoute({
      route: '/login',
      handlers: [http.post(`${api}/auth/login`, res)],
    })
    await fillAndSubmit(r)
    return (await screen.findByRole('alert')).textContent
  }

  it('login_401_showsGenericInvalidCredentials', async () => {
    const text = await failWith(() =>
      HttpResponse.json(
        { status: 401, code: 'INVALID_CREDENTIALS', detail: 'raw detail' },
        { status: 401 },
      ),
    )
    expect(text).toContain('E-mail ou senha incorretos')
    expect(document.body.textContent).not.toContain('raw detail')
  })

  it('login_serverError_showsItsOwnMessage', async () => {
    const text = await failWith(() =>
      HttpResponse.json(
        { status: 500, code: 'INTERNAL_ERROR', detail: 'raw detail' },
        { status: 500 },
      ),
    )
    expect(text).toContain('Algo deu errado do nosso lado')
    expect(text).not.toContain('incorretos')
    expect(document.body.textContent).not.toContain('raw detail')
  })

  it('login_networkError_showsItsOwnMessage', async () => {
    const text = await failWith(() => HttpResponse.error())
    expect(text).not.toContain('incorretos')
    expect(text).toContain('conexão')
  })

  it('login_afterPasswordReset_showsNotice', async () => {
    await renderRoute({ route: '/login?reset=1' })
    expect(screen.getByRole('status')).toBeTruthy()
  })
})
