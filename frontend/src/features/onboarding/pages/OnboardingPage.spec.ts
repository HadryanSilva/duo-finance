import { vi } from 'vitest'
import { screen, within } from '@testing-library/dom'
import { http, HttpResponse, type RequestHandler } from 'msw'
import { describe, expect, it } from 'vitest'
import { isOnboarded } from '@/shared/session/onboarding'
import type { AccountResponse, MemberResponse } from '@/shared/api/types'
import { renderRoute, testUser } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, detail: 'raw' }, { status })
const account: AccountResponse = {
  id: testUser.accountId,
  name: 'Ana',
  currency: 'EUR',
  members: [],
  pendingInvitation: null,
}
const member = (userId: string, displayName: string): MemberResponse => ({
  userId,
  displayName,
  email: `${displayName.toLowerCase()}@x.com`,
  joinedAt: '2026-01-01T00:00:00Z',
})

function setup(session: Record<string, unknown> = {}, extra: RequestHandler[] = []) {
  const calls: Record<string, unknown> = {}
  return {
    calls,
    open: () =>
      renderRoute({
        route: '/onboarding',
        session,
        onboarded: false,
        handlers: [
          // earlier handlers win, so per-test overrides come first
          ...extra,
          http.get(`${api}/me`, () => HttpResponse.json({ ...testUser, ...session })),
          http.get(`${api}/account`, () => HttpResponse.json(account)),
          http.get(`${api}/bank-accounts`, () => HttpResponse.json([])),
          http.patch(`${api}/account`, async ({ request }) => {
            calls.rename = await request.json()
            return HttpResponse.json(account)
          }),
          http.post(`${api}/account/invitations`, async ({ request }) => {
            calls.invite = await request.json()
            return HttpResponse.json({
              id: 'i1',
              email: 'b@x.com',
              expiresAt: '2026-12-01T00:00:00Z',
            })
          }),
          http.post(`${api}/bank-accounts`, async ({ request }) => {
            calls.bank = await request.json()
            return HttpResponse.json({ id: 'b1' })
          }),
        ],
      }),
  }
}

async function toInviteStep(r: Awaited<ReturnType<ReturnType<typeof setup>['open']>>) {
  await screen.findByLabelText('Nome do casal')
  await r.user.click(screen.getByRole('button', { name: 'Continuar' }))
}

describe('OnboardingPage', () => {
  it('completesAllSteps_andMarksOnboarded', async () => {
    const s = setup()
    const r = await s.open()
    const name = (await screen.findByLabelText('Nome do casal')) as HTMLInputElement
    await screen.findByDisplayValue('Ana')
    await r.user.clear(name)
    await r.user.type(name, 'Ana e Bia')
    await r.user.click(screen.getByRole('button', { name: 'Continuar' }))

    await r.user.type(await screen.findByLabelText('E-mail do parceiro'), 'b@x.com')
    await r.user.click(screen.getByRole('button', { name: 'Enviar convite' }))
    expect((await screen.findByRole('status')).textContent).toContain(
      'Convite enviado para b@x.com',
    )
    expect(s.calls.rename).toEqual({ name: 'Ana e Bia' })
    expect(s.calls.invite).toEqual({ email: 'b@x.com' })
    await r.user.click(screen.getByRole('button', { name: 'Continuar' }))

    await r.user.type(await screen.findByLabelText('Nome da conta'), 'Nubank')
    await r.user.selectOptions(screen.getByLabelText('Tipo'), 'SAVINGS')
    const balance = screen.getByLabelText('Saldo inicial')
    await r.user.click(balance)
    await r.user.keyboard('{Control>}a{/Control}1.250,5')
    await r.user.click(screen.getByRole('button', { name: 'Concluir' }))

    await vi.waitFor(() => expect(r.router.currentRoute.value.path).toBe('/dashboard'))
    expect(s.calls.bank).toEqual({
      name: 'Nubank',
      type: 'SAVINGS',
      initialBalanceCents: 125050,
    })
    expect(isOnboarded(testUser.id)).toBe(true)
  })

  it('skippingSteps_stillMarksOnboarded', async () => {
    const s = setup()
    const r = await s.open()
    await toInviteStep(r)
    await r.user.click(await screen.findByRole('button', { name: 'Pular' }))
    await screen.findByLabelText('Nome da conta')
    expect(isOnboarded(testUser.id)).toBe(false)
    await r.user.click(screen.getByRole('button', { name: 'Pular' }))
    await vi.waitFor(() => expect(r.router.currentRoute.value.path).toBe('/dashboard'))
    expect(isOnboarded(testUser.id)).toBe(true)
    expect(s.calls.invite).toBeUndefined()
    expect(s.calls.bank).toBeUndefined()
  })

  it('invite_unverifiedEmail_showsResendAndSkip', async () => {
    let resent = 0
    const s = setup({ emailVerified: false }, [
      http.post(`${api}/auth/resend-verification`, () => {
        resent++
        return new HttpResponse(null, { status: 202 })
      }),
    ])
    const r = await s.open()
    await toInviteStep(r)
    expect((await screen.findByRole('alert')).textContent).toContain(
      'Confirme seu e-mail antes de continuar.',
    )
    expect(screen.queryByLabelText('E-mail do parceiro')).toBeNull()
    await r.user.click(screen.getByRole('button', { name: 'Reenviar verificação' }))
    expect(await screen.findByRole('status')).toBeTruthy()
    expect(resent).toBe(1)
    expect(screen.getByRole('button', { name: 'Pular' })).toBeTruthy()
  })

  it('invite_serverEmailNotVerified_showsSameNotice', async () => {
    const s = setup({}, [
      http.post(`${api}/account/invitations`, () => problem(403, 'EMAIL_NOT_VERIFIED')),
    ])
    const r = await s.open()
    await toInviteStep(r)
    await r.user.type(await screen.findByLabelText('E-mail do parceiro'), 'b@x.com')
    await r.user.click(screen.getByRole('button', { name: 'Enviar convite' }))
    expect((await screen.findByRole('alert')).textContent).toContain('Confirme seu e-mail')
    expect(screen.getByRole('button', { name: 'Reenviar verificação' })).toBeTruthy()
    expect(screen.queryByLabelText('E-mail do parceiro')).toBeNull()
  })

  it.each([
    ['INVITE_SELF', 'Você não pode aceitar o próprio convite.'],
    ['INVITE_PENDING_EXISTS', 'Já existe um convite pendente para esta conta.'],
  ])('invite_%s_isShownTranslated', async (code, text) => {
    const s = setup({}, [http.post(`${api}/account/invitations`, () => problem(409, code))])
    const r = await s.open()
    await toInviteStep(r)
    await r.user.type(await screen.findByLabelText('E-mail do parceiro'), 'b@x.com')
    await r.user.click(screen.getByRole('button', { name: 'Enviar convite' }))
    const alert = await screen.findByRole('alert')
    expect(within(alert).getByText(text)).toBeTruthy()
    expect(screen.getByLabelText('E-mail do parceiro')).toBeTruthy()
  })

  it('bankAccount_blankName_isRejectedBeforeSending', async () => {
    const s = setup()
    const r = await s.open()
    await toInviteStep(r)
    await r.user.click(await screen.findByRole('button', { name: 'Pular' }))
    await r.user.click(await screen.findByRole('button', { name: 'Concluir' }))
    expect(await screen.findByText('Preencha este campo.')).toBeTruthy()
    expect(s.calls.bank).toBeUndefined()
  })

  it('invite_emailVerifiedSinceSignIn_showsTheInviteForm', async () => {
    // The session was restored before the user opened the link, so it still says unverified;
    // /me already knows better.
    const s = setup({ emailVerified: false }, [
      http.get(`${api}/me`, () => HttpResponse.json({ ...testUser, emailVerified: true })),
    ])
    const r = await s.open()
    await toInviteStep(r)
    expect(await screen.findByLabelText('E-mail do parceiro')).toBeTruthy()
    expect(screen.queryByText('Confirme seu e-mail antes de continuar.')).toBeNull()
  })

  it('existingCouple_onNewDevice_goesToDashboard', async () => {
    const couple = { ...account, members: [member('u1', 'Ana'), member('u2', 'Bia')] }
    const s = setup({}, [http.get(`${api}/account`, () => HttpResponse.json(couple))])
    const r = await s.open()
    await vi.waitFor(() => expect(r.router.currentRoute.value.path).toBe('/dashboard'))
    expect(isOnboarded(testUser.id)).toBe(true)
  })

  it('soloAccountWithBankAccount_onNewDevice_goesToDashboard', async () => {
    const s = setup({}, [
      http.get(`${api}/bank-accounts`, () => HttpResponse.json([{ id: 'b1', name: 'Nubank' }])),
    ])
    const r = await s.open()
    await vi.waitFor(() => expect(r.router.currentRoute.value.path).toBe('/dashboard'))
    expect(isOnboarded(testUser.id)).toBe(true)
  })

  it('brandNewSoloAccount_stillSeesTheWizard', async () => {
    const s = setup()
    const r = await s.open()
    expect(await screen.findByLabelText('Nome do casal')).toBeTruthy()
    expect(r.router.currentRoute.value.path).toBe('/onboarding')
    expect(isOnboarded(testUser.id)).toBe(false)
  })
})
