import { screen, waitFor } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type { AuthResponse } from '@/shared/api/types'
import { useSession } from '@/shared/session/session-store'
import { renderRoute, testUser } from '@/test/render'
import { useGoogleSignup, type GoogleSignup } from '../google/google-signup-store'

const gsi = vi.hoisted(() => ({ initialize: vi.fn(), renderButton: vi.fn() }))
vi.mock('../google/load-gsi', () => ({ loadGsi: () => Promise.resolve(gsi) }))

const api = `${window.location.origin}/api/v1`
const authResponse: AuthResponse = { accessToken: 'new-token', expiresIn: 900, user: testUser }
const mode = (m: 'OPEN' | 'INVITE_ONLY') =>
  http.get(`${api}/public/registration-mode`, () =>
    HttpResponse.json({ mode: m, googleClientId: 'cid' }),
  )
const problem = (status: number, code: string) => HttpResponse.json({ status, code }, { status })
const signup: GoogleSignup = {
  ticket: 'tk',
  email: 'ana@example.com',
  displayName: 'Ana Google',
  credential: 'cred',
}
const invitePreview = (email: string) =>
  http.get(`${api}/account/invitations/inv-tok`, () =>
    HttpResponse.json({
      inviterName: 'Bia',
      accountName: 'Casa',
      email,
      existingUser: false,
    }),
  )

async function open(
  handlers: Parameters<typeof renderRoute>[0]['handlers'],
  state: GoogleSignup | null = signup,
) {
  const r = await renderRoute({ route: '/login', handlers })
  if (state) useGoogleSignup(r.pinia).set(state)
  await r.router.push('/register/google')
  return r
}

const submit = () => screen.getByRole('button', { name: 'Concluir cadastro' })

describe('GoogleSignupPage', () => {
  it('googleSignup_withoutState_redirectsToLogin', async () => {
    const r = await open([mode('OPEN')], null)
    await waitFor(() => expect(r.router.currentRoute.value.path).toBe('/login'))
  })

  it('googleSignup_inviteOnlyWithoutInvite_requiresAndSendsAccessCode', async () => {
    let body: unknown
    const r = await open([
      mode('INVITE_ONLY'),
      http.post(`${api}/auth/google/complete`, async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(authResponse, { status: 201 })
      }),
    ])
    const code = await screen.findByLabelText(/^Código de acesso/)
    expect((screen.getByLabelText(/^E-mail/) as HTMLInputElement).value).toBe('ana@example.com')
    expect((screen.getByLabelText(/^Nome/) as HTMLInputElement).value).toBe('Ana Google')
    await r.user.click(screen.getByRole('checkbox'))
    await r.user.click(submit())
    expect(await screen.findByText('Preencha este campo.')).toBeTruthy()
    expect(body).toBeUndefined()
    await r.user.type(code, 'ABCD-1234')
    await r.user.click(submit())
    await waitFor(() => expect(r.router.currentRoute.value.path).toBe('/onboarding'))
    expect(body).toMatchObject({
      signupTicket: 'tk',
      displayName: 'Ana Google',
      acceptTerms: true,
      termsVersion: '2026-11',
      accessCode: 'ABCD-1234',
    })
    expect(useSession(r.pinia).accessToken).toBe('new-token')
    expect(useGoogleSignup(r.pinia).signup).toBeNull()
  })

  it('googleSignup_inviteForSameEmail_hidesCodeAndSendsInvitationToken', async () => {
    let body: Record<string, unknown> = {}
    const r = await open(
      [
        mode('INVITE_ONLY'),
        invitePreview('Ana@Example.com'),
        http.post(`${api}/auth/google/complete`, async ({ request }) => {
          body = (await request.json()) as Record<string, unknown>
          return HttpResponse.json(authResponse, { status: 201 })
        }),
      ],
      { ...signup, invitationToken: 'inv-tok' },
    )
    await waitFor(() => expect((submit() as HTMLButtonElement).disabled).toBe(false))
    expect(screen.queryByLabelText(/^Código de acesso/)).toBeNull()
    await r.user.click(screen.getByRole('checkbox'))
    await r.user.click(submit())
    await waitFor(() => expect(r.router.currentRoute.value.path).toBe('/dashboard'))
    expect(body.invitationToken).toBe('inv-tok')
    expect(body).not.toHaveProperty('accessCode')
  })

  it('googleSignup_inviteForOtherEmail_warnsAndShowsCodeField', async () => {
    let body: Record<string, unknown> = {}
    const r = await open(
      [
        mode('INVITE_ONLY'),
        invitePreview('bia@example.com'),
        http.post(`${api}/auth/google/complete`, async ({ request }) => {
          body = (await request.json()) as Record<string, unknown>
          return HttpResponse.json(authResponse, { status: 201 })
        }),
      ],
      { ...signup, invitationToken: 'inv-tok' },
    )
    expect((await screen.findByRole('status')).textContent).toContain('bia@example.com')
    await r.user.type(await screen.findByLabelText(/^Código de acesso/), 'CODE-1')
    await r.user.click(screen.getByRole('checkbox'))
    await r.user.click(submit())
    await waitFor(() => expect(r.router.currentRoute.value.path).toBe('/onboarding'))
    expect(body.accessCode).toBe('CODE-1')
    expect(body).not.toHaveProperty('invitationToken')
  })

  it('googleSignup_expiredTicket_showsNoticeAndGoogleButton', async () => {
    gsi.initialize.mockClear()
    const r = await open([
      mode('OPEN'),
      http.post(`${api}/auth/google/complete`, () => problem(401, 'GOOGLE_SIGNUP_TICKET_INVALID')),
    ])
    await r.user.click(await screen.findByRole('checkbox'))
    await r.user.click(submit())
    expect((await screen.findByRole('alert')).textContent).toContain('expirou')
    expect(useGoogleSignup(r.pinia).signup).toBeNull()
    expect(r.router.currentRoute.value.path).toBe('/register/google')
    await waitFor(() => expect(gsi.initialize).toHaveBeenCalled())
    expect(screen.getByRole('group', { name: 'Continuar com o Google' })).toBeTruthy()
  })

  it('googleSignup_emailAlreadyRegistered_retriesGoogleLoginAndContinues', async () => {
    let credential: unknown
    const r = await open([
      mode('OPEN'),
      http.post(`${api}/auth/google/complete`, () => problem(409, 'EMAIL_ALREADY_REGISTERED')),
      http.post(`${api}/auth/google`, async ({ request }) => {
        credential = ((await request.json()) as { credential: string }).credential
        return HttpResponse.json({
          status: 'AUTHENTICATED',
          accessToken: 'linked-token',
          expiresIn: 900,
          user: testUser,
        })
      }),
    ])
    await r.user.click(await screen.findByRole('checkbox'))
    await r.user.click(submit())
    await waitFor(() => expect(r.router.currentRoute.value.path).toBe('/dashboard'))
    expect(credential).toBe('cred')
    expect(useSession(r.pinia).accessToken).toBe('linked-token')
  })

  it('googleSignup_emailAlreadyRegistered_withoutLogin_goesToLoginPage', async () => {
    const r = await open([
      mode('OPEN'),
      http.post(`${api}/auth/google/complete`, () => problem(409, 'EMAIL_ALREADY_REGISTERED')),
      http.post(`${api}/auth/google`, () => problem(409, 'GOOGLE_ACCOUNT_CONFLICT')),
    ])
    await r.user.click(await screen.findByRole('checkbox'))
    await r.user.click(submit())
    await waitFor(() => expect(r.router.currentRoute.value.path).toBe('/login'))
    expect(useGoogleSignup(r.pinia).signup).toBeNull()
  })

  it('googleSignup_accessCodeInvalid_showsFormError', async () => {
    const r = await open([
      mode('OPEN'),
      http.post(`${api}/auth/google/complete`, () => problem(400, 'ACCESS_CODE_INVALID')),
    ])
    await r.user.click(await screen.findByRole('checkbox'))
    await r.user.click(submit())
    expect((await screen.findByRole('alert')).textContent).toContain('código')
    expect(r.router.currentRoute.value.path).toBe('/register/google')
  })
})
