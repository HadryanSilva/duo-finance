import { waitFor } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { toast } from 'vue-sonner'
import type { GoogleLoginResponse } from '@/shared/api/types'
import { useSession } from '@/shared/session/session-store'
import { render, renderRoute, testUser } from '@/test/render'
import GoogleButton from './GoogleButton.vue'
import { useGoogleSignup } from './google-signup-store'

const gsi = vi.hoisted(() => ({
  initialize: vi.fn(),
  renderButton: vi.fn(),
}))
vi.mock('./load-gsi', () => ({ loadGsi: () => Promise.resolve(gsi) }))

const api = `${window.location.origin}/api/v1`
const config = (googleClientId: string | null) =>
  http.get(`${api}/public/registration-mode`, () =>
    HttpResponse.json({ mode: 'OPEN', googleClientId }),
  )
const googleLogin = (res: () => Response) => http.post(`${api}/auth/google`, res)

/** Fires the callback the button handed to Google, as if the user picked an account. */
const pickAccount = () => {
  const { callback } = gsi.initialize.mock.calls[0]![0] as {
    callback: (r: { credential: string }) => void
  }
  callback({ credential: 'google-id-token' })
}

describe('GoogleButton', () => {
  beforeEach(() => {
    gsi.initialize.mockClear()
    gsi.renderButton.mockClear()
  })
  afterEach(() => vi.restoreAllMocks())

  it('initializes GSI in popup mode with the current locale', async () => {
    await render(GoogleButton, { locale: 'en' }, { props: { clientId: 'cid' } })
    await waitFor(() => expect(gsi.renderButton).toHaveBeenCalled())
    expect(gsi.initialize).toHaveBeenCalledWith(
      expect.objectContaining({ client_id: 'cid', ux_mode: 'popup' }),
    )
    expect(gsi.renderButton.mock.calls[0]![1]).toMatchObject({ locale: 'en' })
  })

  it('AUTHENTICATED applies the session and redirects like the password login', async () => {
    const r = await renderRoute({
      route: '/login?redirect=/transactions',
      handlers: [
        config('cid'),
        googleLogin(() =>
          HttpResponse.json({
            status: 'AUTHENTICATED',
            accessToken: 'g-token',
            expiresIn: 900,
            user: testUser,
          } satisfies Partial<GoogleLoginResponse>),
        ),
      ],
    })
    await waitFor(() => expect(gsi.initialize).toHaveBeenCalled())
    pickAccount()
    await waitFor(() => expect(r.router.currentRoute.value.fullPath).toBe('/transactions'))
    expect(useSession(r.pinia).accessToken).toBe('g-token')
  })

  it('SIGNUP_REQUIRED stores the ticket and goes to /register/google', async () => {
    const r = await render(
      GoogleButton,
      {
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
      },
      { props: { clientId: 'cid', invitationToken: 'inv-1' } },
    )
    const push = vi.spyOn(r.router, 'push')
    await waitFor(() => expect(gsi.initialize).toHaveBeenCalled())
    pickAccount()
    await waitFor(() => expect(push).toHaveBeenCalledWith('/register/google'))
    expect(useGoogleSignup(r.pinia).signup).toEqual({
      ticket: 'ticket-1',
      email: 'ana@example.com',
      displayName: 'Ana',
      invitationToken: 'inv-1',
      credential: 'google-id-token',
    })
    expect(useSession(r.pinia).accessToken).toBeNull()
  })

  it('GOOGLE_ACCOUNT_CONFLICT shows the translated message', async () => {
    const error = vi.spyOn(toast, 'error').mockImplementation(() => 1)
    await render(
      GoogleButton,
      {
        handlers: [
          googleLogin(() =>
            HttpResponse.json(
              { status: 409, code: 'GOOGLE_ACCOUNT_CONFLICT', detail: 'raw detail' },
              { status: 409 },
            ),
          ),
        ],
      },
      { props: { clientId: 'cid' } },
    )
    await waitFor(() => expect(gsi.initialize).toHaveBeenCalled())
    pickAccount()
    await waitFor(() =>
      expect(error).toHaveBeenCalledWith('Este e-mail já está vinculado a outra conta Google.'),
    )
  })

  it('is not shown on the login page when googleClientId is null', async () => {
    await renderRoute({ route: '/login', handlers: [config(null)] })
    await new Promise((r) => setTimeout(r, 50))
    expect(gsi.initialize).not.toHaveBeenCalled()
  })

  it('is shown on the register page when googleClientId is set', async () => {
    await renderRoute({ route: '/register', handlers: [config('cid')] })
    await waitFor(() => expect(gsi.initialize).toHaveBeenCalled())
  })
})
