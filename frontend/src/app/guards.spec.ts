import { describe, expect, it } from 'vitest'
import App from '@/app/App.vue'
import { render } from '@/test/render'

const path = (r: Awaited<ReturnType<typeof render>>) => r.router.currentRoute.value.fullPath

describe('guards', () => {
  it('redirects anonymous user to login with redirect param', async () => {
    const r = await render(App, { route: '/budgets?month=2026-03' })
    expect(r.router.currentRoute.value.path).toBe('/login')
    expect(r.router.currentRoute.value.query.redirect).toBe('/budgets?month=2026-03')
  })

  it('lets anonymous users reach public pages', async () => {
    for (const route of ['/', '/register', '/invite/abc', '/privacy', '/terms']) {
      const r = await render(App, { route })
      expect(path(r), route).toBe(route)
    }
  })

  it('redirects authenticated user away from login', async () => {
    const r = await render(App, { route: '/login', session: {} })
    expect(path(r)).toBe('/dashboard')
    await r.router.push('/register')
    expect(path(r)).toBe('/dashboard')
  })

  it('blocks admin route for regular user', async () => {
    const user = await render(App, { route: '/admin', session: { role: 'USER' } })
    expect(path(user)).toBe('/dashboard')
    const admin = await render(App, { route: '/admin', session: { role: 'ADMIN' } })
    expect(path(admin)).toBe('/admin')
  })

  it('sends first-time user to onboarding once', async () => {
    const r = await render(App, { route: '/dashboard', session: {}, onboarded: false })
    expect(path(r)).toBe('/onboarding')
    await r.router.push('/dashboard')
    expect(path(r)).toBe('/dashboard')
  })

  it('does not send onboarded users or invite links to onboarding', async () => {
    const onboarded = await render(App, { route: '/transactions', session: {} })
    expect(path(onboarded)).toBe('/transactions')
    const invite = await render(App, {
      route: '/invite/tok',
      session: { id: '00000000-0000-4000-8000-000000000002' },
      onboarded: false,
    })
    expect(path(invite)).toBe('/invite/tok')
    await invite.router.push('/dashboard')
    expect(path(invite)).toBe('/onboarding')
  })

  it('renders the not-found page for unknown paths', async () => {
    const r = await render(App, { route: '/nope/deeper' })
    expect(r.wrapper.get('h1').text()).toBe('Página não encontrada')
  })
})
