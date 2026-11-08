import { screen } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { renderRoute } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const mode = (m: 'OPEN' | 'INVITE_ONLY') =>
  http.get(`${api}/public/registration-mode`, () => HttpResponse.json({ mode: m }))

describe('LandingPage', () => {
  it('landing_showsRegisterLinkOnlyInOpenMode', async () => {
    await renderRoute({ route: '/', handlers: [mode('OPEN')] })
    expect(await screen.findByRole('link', { name: 'Criar conta' })).toBeTruthy()
    expect(screen.getAllByRole('link', { name: 'Entrar' }).length).toBeGreaterThan(0)
    expect(screen.getByRole('link', { name: 'Privacidade' }).getAttribute('href')).toBe('/privacy')
    expect(screen.getByRole('link', { name: 'Termos de uso' }).getAttribute('href')).toBe('/terms')
  })

  it('landing_hidesRegisterLinkInInviteOnlyMode', async () => {
    await renderRoute({ route: '/', handlers: [mode('INVITE_ONLY')] })
    await screen.findByRole('button', { name: 'Entrar na lista' })
    await new Promise((r) => setTimeout(r, 50))
    expect(screen.queryByRole('link', { name: 'Criar conta' })).toBeNull()
  })

  it('landing_redirectsAuthenticatedUser', async () => {
    const r = await renderRoute({ route: '/', session: {}, handlers: [mode('OPEN')] })
    expect(r.router.currentRoute.value.path).toBe('/dashboard')
  })

  it('legal_privacyRendersPlaceholderText', async () => {
    await renderRoute({ route: '/privacy' })
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Política de privacidade')
    expect(screen.getByText('[texto jurídico a ser fornecido]')).toBeTruthy()
  })

  it('legal_termsRendersPlaceholderText', async () => {
    await renderRoute({ route: '/terms', locale: 'en' })
    expect(screen.getByRole('heading', { level: 1 }).textContent).toBe('Terms of use')
    expect(screen.getByText('[legal text to be provided]')).toBeTruthy()
  })
})
