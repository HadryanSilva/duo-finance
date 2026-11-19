import { screen, waitFor } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { GoogleLoginResponse } from '@/shared/api/types'
import { useSession } from '@/shared/session/session-store'
import { googleAnswer } from '@/test/google'
import { renderRoute, testUser } from '@/test/render'
import { startGoogleSignIn } from '../google/google-redirect'
import { useGoogleSignup } from '../google/google-signup-store'

const leaveApp = vi.hoisted(() => vi.fn())
vi.mock('../google/leave-app', () => ({ leaveApp }))

const api = `${window.location.origin}/api/v1`
const googleLogin = (res: (body: unknown) => Response) =>
  http.post(`${api}/auth/google`, async ({ request }) => res(await request.json()))

/** Starts a sign-in as a page would, and returns the callback route Google would send the browser to. */
function signedInAtGoogle(
  start: { returnTo?: string; cancelTo?: string; invitationToken?: string } = {},
  over: Record<string, string> = {},
) {
  startGoogleSignIn({ clientId: 'cid', returnTo: '/dashboard', cancelTo: '/login', ...start })
  return `/auth/google/callback${googleAnswer(leaveApp.mock.calls.at(-1)![0] as string, over)}`
}

describe('GoogleCallbackPage', () => {
  beforeEach(() => {
    leaveApp.mockClear()
    sessionStorage.clear()
  })

  it('AUTHENTICATED signs in and goes where the sign-in started for', async () => {
    let body: unknown
    const r = await renderRoute({
      route: signedInAtGoogle({ returnTo: '/transactions' }),
      handlers: [
        googleLogin((b) => {
          body = b
          return HttpResponse.json({
            status: 'AUTHENTICATED',
            accessToken: 'g-token',
            expiresIn: 900,
            user: testUser,
          } satisfies Partial<GoogleLoginResponse>)
        }),
      ],
    })
    await waitFor(() => expect(r.router.currentRoute.value.fullPath).toBe('/transactions'))
    expect(useSession(r.pinia).accessToken).toBe('g-token')
    expect(body).toMatchObject({ credential: expect.stringMatching(/\./) })
  })

  it('SIGNUP_REQUIRED keeps the ticket and invitation and goes to /register/google', async () => {
    const r = await renderRoute({
      route: signedInAtGoogle({ invitationToken: 'inv-1' }),
      handlers: [
        googleLogin(() =>
          HttpResponse.json({
            status: 'SIGNUP_REQUIRED',
            signupTicket: 'ticket-1',
            email: 'ana@example.com',
            displayName: 'Ana',
          }),
        ),
      ],
    })
    await waitFor(() => expect(r.router.currentRoute.value.path).toBe('/register/google'))
    expect(useGoogleSignup(r.pinia).signup).toMatchObject({
      ticket: 'ticket-1',
      email: 'ana@example.com',
      displayName: 'Ana',
      invitationToken: 'inv-1',
    })
    expect(useSession(r.pinia).accessToken).toBeNull()
  })

  it('a server rejection shows the translated message', async () => {
    await renderRoute({
      route: signedInAtGoogle(),
      handlers: [
        googleLogin(() =>
          HttpResponse.json(
            { status: 409, code: 'GOOGLE_ACCOUNT_CONFLICT', detail: 'raw detail' },
            { status: 409 },
          ),
        ),
      ],
    })
    expect((await screen.findByRole('alert')).textContent).toContain(
      'Este e-mail já está vinculado a outra conta Google.',
    )
    expect(screen.getByRole('link', { name: 'Voltar para entrar' })).toBeTruthy()
  })

  it('an answer this tab did not start is refused without calling the server', async () => {
    let called = false
    await renderRoute({
      route: signedInAtGoogle({}, { state: 'forged' }),
      handlers: [
        googleLogin(() => {
          called = true
          return HttpResponse.json({})
        }),
      ],
    })
    expect((await screen.findByRole('alert')).textContent).toContain('Não foi possível concluir')
    expect(called).toBe(false)
  })

  it('cancelling on Google goes back to the page that started the sign-in', async () => {
    startGoogleSignIn({ clientId: 'cid', returnTo: '/dashboard', cancelTo: '/register' })
    const state = new URL(leaveApp.mock.calls[0]![0] as string).searchParams.get('state')
    const r = await renderRoute({
      route: `/auth/google/callback#error=access_denied&state=${state}`,
    })
    await waitFor(() => expect(r.router.currentRoute.value.path).toBe('/register'))
  })
})
