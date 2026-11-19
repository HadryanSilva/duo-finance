import { screen, waitFor } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { AuthResponse } from '@/shared/api/types'
import { useSession } from '@/shared/session/session-store'
import { googleAnswer } from '@/test/google'
import { renderRoute, testUser } from '@/test/render'

const leaveApp = vi.hoisted(() => vi.fn())
vi.mock('../google/leave-app', () => ({ leaveApp }))
/** Clicks the Google button and comes back from Google signed in, as the browser would. */
async function viaGoogle(r: Awaited<ReturnType<typeof renderRoute>>) {
  leaveApp.mockClear()
  await r.user.click(await screen.findByRole('button', { name: 'Continuar com o Google' }))
  await r.router.push(`/auth/google/callback${googleAnswer(leaveApp.mock.calls[0]![0] as string)}`)
}

const api = `${window.location.origin}/api/v1`
const authResponse: AuthResponse = { accessToken: 'new-token', expiresIn: 900, user: testUser }

async function fillAndSubmit(r: Awaited<ReturnType<typeof renderRoute>>) {
  await r.user.type(screen.getByLabelText(/^E-mail/), 'ana@example.com')
  await r.user.type(screen.getByLabelText(/^Senha/), 'secret123')
  await r.user.click(screen.getByRole('button', { name: 'Entrar' }))
}

describe('LoginPage', () => {
  beforeEach(() => leaveApp.mockClear())

  it('login_googleClientIdNull_hasNoGoogleButton', async () => {
    let asked = false
    await renderRoute({
      route: '/login',
      handlers: [
        http.get(`${api}/public/registration-mode`, () => {
          asked = true
          return HttpResponse.json({ mode: 'OPEN', googleClientId: null })
        }),
      ],
    })
    await waitFor(() => expect(asked).toBe(true))
    // let the query result reach the page before asserting absence
    await new Promise((r) => setTimeout(r, 0))
    expect(screen.queryByRole('button', { name: 'Continuar com o Google' })).toBeNull()
  })

  it('login_google_returnsToRedirectParam', async () => {
    const r = await renderRoute({
      route: '/login?redirect=/transactions',
      handlers: [
        http.get(`${api}/public/registration-mode`, () =>
          HttpResponse.json({ mode: 'OPEN', googleClientId: 'cid' }),
        ),
        http.post(`${api}/auth/google`, () =>
          HttpResponse.json({ status: 'AUTHENTICATED', ...authResponse }),
        ),
      ],
    })
    await viaGoogle(r)
    await waitFor(() => expect(r.router.currentRoute.value.fullPath).toBe('/transactions'))
    expect(useSession(r.pinia).accessToken).toBe('new-token')
  })

  it('login_success_redirectsToRedirectParam', async () => {
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
