import { screen, within } from '@testing-library/dom'
import { http, HttpResponse, type RequestHandler } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import type { AccountResponse, AuthResponse, MeResponse } from '@/shared/api/types'
import { isOnboarded } from '@/shared/session/onboarding'
import { useSession } from '@/shared/session/session-store'
import { renderRoute, testUser } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, detail: 'raw' }, { status })
const auth = (token: string, user: Partial<MeResponse> = {}): AuthResponse => ({
  accessToken: token,
  expiresIn: 900,
  user: { ...testUser, ...user },
})

const ana = {
  userId: testUser.id,
  displayName: 'Ana Souza',
  email: 'ana@example.com',
  joinedAt: '2026-01-01T00:00:00Z',
}
const bia = {
  userId: 'u2',
  displayName: 'Bia Lima',
  email: 'bia@example.com',
  joinedAt: '2026-02-01T00:00:00Z',
}
const solo: AccountResponse = {
  id: testUser.accountId,
  name: 'Casa da Ana',
  currency: 'EUR',
  members: [ana],
  pendingInvitation: null,
}
const full: AccountResponse = { ...solo, name: 'Ana e Bia', members: [ana, bia] }

interface Opts {
  account?: AccountResponse
  me?: Partial<MeResponse>
  extra?: RequestHandler[]
}

async function open({ account = solo, me = {}, extra = [] }: Opts = {}) {
  const refresh = vi.fn(() => problem(401, 'REFRESH_TOKEN_INVALID'))
  const logout = vi.fn(() => new HttpResponse(null, { status: 204 }))
  const r = await renderRoute({
    route: '/settings',
    session: me,
    handlers: [
      // earlier handlers win, so per-test overrides come first
      ...extra,
      http.get(`${api}/me`, () => HttpResponse.json({ ...testUser, ...me })),
      http.get(`${api}/account`, () => HttpResponse.json(account)),
      http.get(`${api}/account/subscription`, () =>
        HttpResponse.json({
          plan: 'BETA',
          status: 'ACTIVE',
          currentPeriodEnd: null,
          hasAccess: true,
        }),
      ),
      http.post(`${api}/auth/refresh`, refresh),
      http.post(`${api}/auth/logout`, logout),
    ],
  })
  return { ...r, refresh, logout, session: useSession(r.pinia) }
}

const region = (name: string) => screen.findByRole('region', { name })

describe('SettingsPage', () => {
  it('changeLanguage_appliesImmediately', async () => {
    let body: unknown
    const r = await open({
      extra: [
        http.patch(`${api}/me`, async ({ request }) => {
          body = await request.json()
          return HttpResponse.json({ ...testUser, locale: 'en' })
        }),
      ],
    })
    const prefs = within(await region('Preferências'))
    await r.user.selectOptions(prefs.getByLabelText('Idioma'), 'en')
    expect(await screen.findByRole('heading', { level: 1, name: 'Settings' })).toBeTruthy()
    expect(body).toEqual({ locale: 'en' })
    expect(r.session.user?.locale).toBe('en')
  })

  it('changeLanguage_failure_keepsTheCurrentLanguage', async () => {
    const r = await open({ extra: [http.patch(`${api}/me`, () => problem(500, 'INTERNAL_ERROR'))] })
    const prefs = within(await region('Preferências'))
    await r.user.selectOptions(prefs.getByLabelText('Idioma'), 'en')
    expect(await prefs.findByText(/Algo deu errado do nosso lado/)).toBeTruthy()
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Configurações')
  })

  it('changeTheme_isClientOnly', async () => {
    const r = await open()
    const prefs = within(await region('Preferências'))
    await r.user.selectOptions(prefs.getByLabelText('Tema'), 'dark')
    expect(document.documentElement.classList.contains('dark')).toBe(true)
    expect(localStorage.getItem('df.theme')).toBe('dark')
  })

  it('profile_savesTheName', async () => {
    let body: unknown
    const r = await open({
      extra: [
        http.patch(`${api}/me`, async ({ request }) => {
          body = await request.json()
          return HttpResponse.json({ ...testUser, displayName: 'Ana Maria' })
        }),
      ],
    })
    const profile = within(await region('Perfil'))
    const name = profile.getByLabelText('Nome')
    await r.user.clear(name)
    await r.user.type(name, 'Ana Maria')
    await r.user.click(profile.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByText('Perfil atualizado.')).toBeTruthy()
    expect(body).toEqual({ displayName: 'Ana Maria' })
    expect(r.session.user?.displayName).toBe('Ana Maria')
    expect(profile.getByText('ana@example.com')).toBeTruthy()
    expect(profile.queryByText('Não verificado')).toBeNull()
  })

  it('profile_unverifiedEmail_showsBadgeAndResend', async () => {
    let resent = 0
    const r = await open({
      me: { emailVerified: false },
      extra: [
        http.post(`${api}/auth/resend-verification`, () => {
          resent++
          return new HttpResponse(null, { status: 204 })
        }),
      ],
    })
    const profile = within(await region('Perfil'))
    expect(profile.getByText('Não verificado')).toBeTruthy()
    await r.user.click(profile.getByRole('button', { name: 'Reenviar verificação' }))
    expect(await screen.findByText(/novo link de confirmação/)).toBeTruthy()
    expect(resent).toBe(1)
  })

  it('changePassword_appliesNewSession', async () => {
    let body: unknown
    const r = await open({
      extra: [
        http.put(`${api}/me/password`, async ({ request }) => {
          body = await request.json()
          return HttpResponse.json(auth('fresh-token'))
        }),
      ],
    })
    const pw = within(await region('Senha'))
    await r.user.type(pw.getByLabelText('Senha atual'), 'old-secret')
    await r.user.type(pw.getByLabelText('Nova senha'), 'new-secret-1')
    await r.user.click(pw.getByRole('button', { name: 'Alterar senha' }))
    expect(await screen.findByText(/Senha alterada/)).toBeTruthy()
    expect(body).toEqual({ currentPassword: 'old-secret', newPassword: 'new-secret-1' })
    expect(r.session.accessToken).toBe('fresh-token')
    expect((pw.getByLabelText('Senha atual') as HTMLInputElement).value).toBe('')
  })

  it('changePassword_wrongCurrent_showsFieldErrorWithoutLoggingOut', async () => {
    const r = await open({
      extra: [http.put(`${api}/me/password`, () => problem(401, 'INVALID_CREDENTIALS'))],
    })
    const pw = within(await region('Senha'))
    await r.user.type(pw.getByLabelText('Senha atual'), 'wrong')
    await r.user.type(pw.getByLabelText('Nova senha'), 'new-secret-1')
    await r.user.click(pw.getByRole('button', { name: 'Alterar senha' }))
    expect(await pw.findByText('A senha atual está incorreta.')).toBeTruthy()
    expect(r.refresh).not.toHaveBeenCalled()
    expect(r.session.status).toBe('authenticated')
    expect(r.router.currentRoute.value.path).toBe('/settings')
  })

  it('couple_soloShowsInvite_fullShowsLeave', async () => {
    const solo1 = await open()
    const soloCouple = within(await region('Casal'))
    expect(await soloCouple.findByRole('button', { name: 'Convidar parceiro' })).toBeTruthy()
    expect(soloCouple.queryByRole('button', { name: 'Sair do casal' })).toBeNull()
    expect(soloCouple.getByDisplayValue('Casa da Ana')).toBeTruthy()
    expect(soloCouple.getByText('Ana Souza')).toBeTruthy()
    solo1.wrapper.unmount()
    document.body.innerHTML = ''

    await open({ account: full })
    const fullCouple = within(await region('Casal'))
    expect(await fullCouple.findByRole('button', { name: 'Sair do casal' })).toBeTruthy()
    expect(fullCouple.queryByRole('button', { name: 'Convidar parceiro' })).toBeNull()
    expect(fullCouple.getByText('Bia Lima')).toBeTruthy()
  })

  it('couple_pendingInvitation_showsEmailExpiryAndHidesInvite', async () => {
    await open({
      account: {
        ...solo,
        pendingInvitation: {
          id: 'i1',
          email: 'bia@example.com',
          expiresAt: '2026-12-01T12:00:00Z',
        },
      },
    })
    const couple = within(await region('Casal'))
    const expected = new Intl.DateTimeFormat('pt-BR', { dateStyle: 'medium' }).format(
      new Date('2026-12-01T12:00:00Z'),
    )
    expect((await couple.findByText(/bia@example\.com · válido até/)).textContent).toContain(
      expected,
    )
    expect(couple.getByRole('button', { name: 'Cancelar convite' })).toBeTruthy()
    expect(couple.queryByRole('button', { name: 'Convidar parceiro' })).toBeNull()
  })

  it('invite_sendsAndRefreshesTheAccount', async () => {
    let account = solo
    let body: unknown
    const r = await open({
      extra: [
        http.get(`${api}/account`, () => HttpResponse.json(account)),
        http.post(`${api}/account/invitations`, async ({ request }) => {
          body = await request.json()
          account = {
            ...solo,
            pendingInvitation: {
              id: 'i1',
              email: 'bia@example.com',
              expiresAt: '2026-12-01T12:00:00Z',
            },
          }
          return HttpResponse.json(account.pendingInvitation, { status: 201 })
        }),
      ],
    })
    const couple = within(await region('Casal'))
    await r.user.type(await couple.findByLabelText('E-mail do parceiro'), 'bia@example.com')
    await r.user.click(couple.getByRole('button', { name: 'Convidar parceiro' }))
    expect(await couple.findByText(/bia@example\.com · válido até/)).toBeTruthy()
    expect(body).toEqual({ email: 'bia@example.com' })
  })

  it('invite_unverifiedEmail_isDisabledWithExplanationAndResend', async () => {
    const r = await open({ me: { emailVerified: false } })
    const couple = within(await region('Casal'))
    const invite = await couple.findByRole('button', { name: 'Convidar parceiro' })
    expect((invite as HTMLButtonElement).disabled).toBe(true)
    expect(couple.getByText('Confirme seu e-mail para convidar seu parceiro.')).toBeTruthy()
    expect(couple.getByRole('button', { name: 'Reenviar verificação' })).toBeTruthy()
    void r
  })

  it('invite_serverError_isShown', async () => {
    const r = await open({
      extra: [http.post(`${api}/account/invitations`, () => problem(409, 'ACCOUNT_FULL'))],
    })
    const couple = within(await region('Casal'))
    await r.user.type(await couple.findByLabelText('E-mail do parceiro'), 'bia@example.com')
    await r.user.click(couple.getByRole('button', { name: 'Convidar parceiro' }))
    expect(await couple.findByText('Esta conta já tem duas pessoas.')).toBeTruthy()
  })

  it('cancelInvitation_refreshesAccount', async () => {
    let account: AccountResponse = {
      ...solo,
      pendingInvitation: { id: 'i1', email: 'bia@example.com', expiresAt: '2026-12-01T12:00:00Z' },
    }
    let cancelled = ''
    const r = await open({
      extra: [
        http.get(`${api}/account`, () => HttpResponse.json(account)),
        http.delete(`${api}/account/invitations/:id`, ({ params }) => {
          cancelled = String(params.id)
          account = solo
          return new HttpResponse(null, { status: 204 })
        }),
      ],
    })
    const couple = within(await region('Casal'))
    await r.user.click(await couple.findByRole('button', { name: 'Cancelar convite' }))
    expect(await couple.findByRole('button', { name: 'Convidar parceiro' })).toBeTruthy()
    expect(cancelled).toBe('i1')
    expect(couple.queryByText(/válido até/)).toBeNull()
  })

  it('renameCouple_sendsTheNewName', async () => {
    let body: unknown
    const r = await open({
      extra: [
        http.patch(`${api}/account`, async ({ request }) => {
          body = await request.json()
          return HttpResponse.json({ ...solo, name: 'Novo nome' })
        }),
      ],
    })
    const couple = within(await region('Casal'))
    const name = await couple.findByDisplayValue('Casa da Ana')
    await r.user.clear(name)
    await r.user.type(name, 'Novo nome')
    await r.user.click(couple.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByText('Nome atualizado.')).toBeTruthy()
    expect(body).toEqual({ name: 'Novo nome' })
  })

  it('leave_replacesSessionAndClearsCache', async () => {
    const r = await open({
      account: full,
      extra: [
        http.post(`${api}/account/leave`, () =>
          HttpResponse.json(auth('solo-token', { accountId: 'acc-new' })),
        ),
        http.get(`${api}/account`, ({ request }) =>
          // after the switch the new token reads the user's new, empty account
          HttpResponse.json(
            request.headers.get('authorization') === 'Bearer solo-token'
              ? { ...solo, id: 'acc-new', name: 'Conta nova' }
              : full,
          ),
        ),
      ],
    })
    expect(isOnboarded(testUser.id)).toBe(true)
    r.queryClient.setQueryData(['transactions'], [{ id: 'old-account-row' }])
    const couple = within(await region('Casal'))
    await r.user.click(await couple.findByRole('button', { name: 'Sair do casal' }))
    const dialog = within(await screen.findByRole('dialog'))
    expect(dialog.getByText(/ficam com o seu parceiro/)).toBeTruthy()
    await r.user.click(dialog.getByRole('button', { name: 'Sair do casal' }))

    await vi.waitFor(() => expect(r.router.currentRoute.value.path).toBe('/onboarding'))
    expect(r.session.accessToken).toBe('solo-token')
    expect(r.session.user?.accountId).toBe('acc-new')
    expect(r.queryClient.getQueryData(['transactions'])).toBeUndefined()
    expect(isOnboarded(testUser.id)).toBe(false)
    // nothing of the old Account stays on screen
    await screen.findByRole('heading', { level: 1 })
    expect(document.body.textContent).not.toContain('Bia Lima')
    expect(document.body.textContent).not.toContain('Ana e Bia')
  })

  it('leave_failure_showsErrorKeepsSessionAndAllowsRetry', async () => {
    const r = await open({
      account: full,
      extra: [http.post(`${api}/account/leave`, () => problem(409, 'NOT_IN_COUPLE'))],
    })
    const couple = within(await region('Casal'))
    await r.user.click(await couple.findByRole('button', { name: 'Sair do casal' }))
    const dialog = within(await screen.findByRole('dialog'))
    await r.user.click(dialog.getByRole('button', { name: 'Sair do casal' }))

    expect(await couple.findByText('Você não faz parte de um casal.')).toBeTruthy()
    expect(r.session.accessToken).toBe('test-access-token')
    expect(r.router.currentRoute.value.path).toBe('/settings')
    const again = await couple.findByRole('button', { name: 'Sair do casal' })
    expect((again as HTMLButtonElement).disabled).toBe(false)
  })

  it('plan_isReadOnly', async () => {
    await open()
    const plan = within(await region('Plano'))
    expect(await plan.findByText('Beta')).toBeTruthy()
    expect(plan.getByText('Ativo')).toBeTruthy()
    expect(plan.queryAllByRole('button')).toHaveLength(0)
    expect(plan.queryAllByRole('textbox')).toHaveLength(0)
  })

  it('deleteAccount_requiresPasswordAndConfirmation', async () => {
    let body: unknown
    let calls = 0
    const r = await open({
      extra: [
        http.delete(`${api}/me`, async ({ request }) => {
          calls++
          body = await request.json()
          return new HttpResponse(null, { status: 204 })
        }),
      ],
    })
    const danger = within(await region('Zona de perigo'))
    await r.user.click(danger.getByRole('button', { name: 'Excluir minha conta' }))
    expect(await danger.findByText('Preencha este campo.')).toBeTruthy()
    expect(screen.queryByRole('dialog')).toBeNull()

    await r.user.type(danger.getByLabelText('Confirme sua senha'), 'my-secret')
    await r.user.click(danger.getByRole('button', { name: 'Excluir minha conta' }))
    const dialog = within(await screen.findByRole('dialog'))
    expect(dialog.getByText(/Ex-membro/)).toBeTruthy()
    expect(calls).toBe(0) // not before the confirmation
    await r.user.click(dialog.getByRole('button', { name: 'Excluir definitivamente' }))

    await vi.waitFor(() => expect(r.router.currentRoute.value.path).toBe('/'))
    expect(body).toEqual({ password: 'my-secret' })
    expect(r.session.status).toBe('anonymous')
    expect(r.session.accessToken).toBeNull()
    expect(r.logout).not.toHaveBeenCalled() // the account is gone: no backend logout
    expect(await screen.findByText('Sua conta foi excluída.')).toBeTruthy()
  })

  it('deleteAccount_wrongPassword_showsError', async () => {
    const r = await open({
      extra: [http.delete(`${api}/me`, () => problem(401, 'INVALID_CREDENTIALS'))],
    })
    const danger = within(await region('Zona de perigo'))
    await r.user.type(danger.getByLabelText('Confirme sua senha'), 'nope')
    await r.user.click(danger.getByRole('button', { name: 'Excluir minha conta' }))
    const dialog = within(await screen.findByRole('dialog'))
    await r.user.click(dialog.getByRole('button', { name: 'Excluir definitivamente' }))

    expect(await danger.findByText('A senha está incorreta.')).toBeTruthy()
    expect(r.refresh).not.toHaveBeenCalled()
    expect(r.session.status).toBe('authenticated')
    expect(r.router.currentRoute.value.path).toBe('/settings')
  })
})
