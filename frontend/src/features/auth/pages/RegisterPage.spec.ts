import { screen, waitFor } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { AuthResponse } from '@/shared/api/types'
import { isOnboarded } from '@/shared/session/onboarding'
import { renderRoute, testUser } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const authResponse: AuthResponse = { accessToken: 'new-token', expiresIn: 900, user: testUser }
const mode = (m: 'OPEN' | 'INVITE_ONLY') =>
  http.get(`${api}/public/registration-mode`, () => HttpResponse.json({ mode: m }))
const register = (onBody?: (b: unknown) => void) =>
  http.post(`${api}/auth/register`, async ({ request }) => {
    onBody?.(await request.json())
    return HttpResponse.json(authResponse, { status: 201 })
  })

type R = Awaited<ReturnType<typeof renderRoute>>
const submitButton = () => screen.getByRole('button', { name: 'Criar conta' }) as HTMLButtonElement
const ready = () => waitFor(() => expect(submitButton().disabled).toBe(false))

async function fill(r: R, opts: { terms?: boolean; code?: string } = {}) {
  const email = screen.getByLabelText(/^E-mail/) as HTMLInputElement
  if (!email.value) await r.user.type(email, 'ana@example.com')
  await r.user.type(screen.getByLabelText(/^Senha/), 'secret123')
  await r.user.type(screen.getByLabelText(/^Nome/), '  Ana Souza  ')
  if (opts.code) await r.user.type(screen.getByLabelText(/^Código de acesso/), opts.code)
  if (opts.terms !== false) await r.user.click(screen.getByRole('checkbox'))
  await r.user.click(submitButton())
}

describe('RegisterPage', () => {
  afterEach(() => vi.restoreAllMocks())

  it('register_hidesCodeField_whenOpenMode', async () => {
    await renderRoute({ route: '/register', handlers: [mode('OPEN')] })
    await ready()
    expect(screen.queryByLabelText(/^Código de acesso/)).toBeNull()
  })

  it('register_hidesCodeField_whenInviteToken', async () => {
    await renderRoute({
      route: '/register?invite=tok123&email=bia@example.com',
      handlers: [mode('INVITE_ONLY')],
    })
    await ready()
    expect(screen.queryByLabelText(/^Código de acesso/)).toBeNull()
    expect((screen.getByLabelText(/^E-mail/) as HTMLInputElement).value).toBe('bia@example.com')
  })

  it('register_showsCodeField_whenInviteOnly', async () => {
    await renderRoute({ route: '/register', handlers: [mode('INVITE_ONLY')] })
    expect(await screen.findByLabelText(/^Código de acesso/)).toBeTruthy()
  })

  it('register_prefillsCodeFromQuery', async () => {
    await renderRoute({
      route: '/register?code=ABCD-1234',
      handlers: [mode('INVITE_ONLY')],
    })
    const code = (await screen.findByLabelText(/^Código de acesso/)) as HTMLInputElement
    expect(code.value).toBe('ABCD-1234')
  })

  it('register_sendsPayloadAndGoesToOnboarding', async () => {
    vi.spyOn(navigator, 'language', 'get').mockReturnValue('pt-BR') // the form defaults to the browser language
    let body: unknown
    const r = await renderRoute({
      route: '/register',
      handlers: [mode('INVITE_ONLY'), register((b) => (body = b))],
    })
    await screen.findByLabelText(/^Código de acesso/)
    await fill(r, { code: 'ABCD-1234' })
    await waitFor(() => expect(r.router.currentRoute.value.fullPath).toBe('/onboarding'))
    expect(body).toEqual({
      email: 'ana@example.com',
      password: 'secret123',
      displayName: 'Ana Souza',
      locale: 'pt-BR',
      acceptTerms: true,
      termsVersion: '2026-11',
      accessCode: 'ABCD-1234',
    })
  })

  it('register_afterInvite_marksOnboardedAndGoesToDashboard', async () => {
    let body: Record<string, unknown> = {}
    const r = await renderRoute({
      route: '/register?invite=tok123&email=bia@example.com',
      handlers: [mode('INVITE_ONLY'), register((b) => (body = b as Record<string, unknown>))],
    })
    await ready()
    await fill(r)
    await waitFor(() => expect(r.router.currentRoute.value.fullPath).toBe('/dashboard'))
    expect(body.invitationToken).toBe('tok123')
    expect(body).not.toHaveProperty('accessCode')
    expect(isOnboarded(testUser.id)).toBe(true)
  })

  it('register_mapsFieldErrorsFromApi', async () => {
    const r = await renderRoute({
      route: '/register',
      handlers: [
        mode('OPEN'),
        http.post(`${api}/auth/register`, () =>
          HttpResponse.json(
            {
              status: 400,
              code: 'VALIDATION_FAILED',
              errors: [{ field: 'password', code: 'Size' }],
            },
            { status: 400 },
          ),
        ),
      ],
    })
    await ready()
    await fill(r)
    const password = screen.getByLabelText(/^Senha/)
    await waitFor(() => expect(password.getAttribute('aria-invalid')).toBe('true'))
    const described = document.getElementById(password.getAttribute('aria-describedby') ?? '')
    expect(described?.textContent).toContain('tamanho')
  })

  it('register_requiresTermsAcceptance', async () => {
    let called = false
    const r = await renderRoute({
      route: '/register',
      handlers: [
        mode('OPEN'),
        http.post(`${api}/auth/register`, () => {
          called = true
          return HttpResponse.json(authResponse)
        }),
      ],
    })
    await ready()
    await fill(r, { terms: false })
    await waitFor(() =>
      expect(screen.getByRole('checkbox').getAttribute('aria-invalid')).toBe('true'),
    )
    expect(called).toBe(false)
  })

  it('register_linksToTermsAndPrivacy', async () => {
    await renderRoute({ route: '/register', handlers: [mode('OPEN')] })
    expect(screen.getByRole('link', { name: /Termos de uso/ }).getAttribute('href')).toBe('/terms')
    expect(screen.getByRole('link', { name: /privacidade/ }).getAttribute('href')).toBe('/privacy')
  })
})
