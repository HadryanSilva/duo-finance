import { http as rest, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import App from '@/app/App.vue'
import { render } from '@/test/render'
import AppLayout from './AppLayout.vue'

const api = `${window.location.origin}/api/v1`
const linkNames = (el: Element) => [...el.querySelectorAll('a')].map((a) => a.textContent?.trim())

describe('AppLayout', () => {
  it('shows admin link only for ADMIN', async () => {
    const regular = await render(AppLayout, { route: '/dashboard', session: { role: 'USER' } })
    const sidebar = regular.wrapper.get('[data-testid="sidebar"]').element
    expect(linkNames(sidebar)).toEqual([
      'Duo Finance',
      'Dashboard',
      'Lançamentos',
      'Contas',
      'Categorias',
      'Orçamentos',
      'Recorrências',
      'Configurações',
    ])
    const admin = await render(AppLayout, { route: '/dashboard', session: { role: 'ADMIN' } })
    const adminLink = admin.wrapper.get('[data-testid="sidebar"] a[href="/admin"]')
    expect(adminLink.text()).toBe('Admin')
  })

  it('renders bottom nav on mobile and sidebar on desktop', async () => {
    const { wrapper } = await render(AppLayout, { route: '/dashboard', session: {} })
    const sidebar = wrapper.get('[data-testid="sidebar"]')
    expect(sidebar.classes()).toEqual(expect.arrayContaining(['hidden', 'md:flex']))
    const bottom = wrapper.get('[data-testid="bottom-nav"]')
    expect(bottom.classes()).toContain('md:hidden')
    expect(linkNames(bottom.element)).toEqual([
      'Dashboard',
      'Lançamentos',
      '',
      'Orçamentos',
      'Configurações',
    ])
    const plus = bottom.get('a[aria-label="Novo lançamento"]')
    expect(plus.attributes('href')).toBe('/transactions?new=1')
  })

  it('translates the navigation in English', async () => {
    const { wrapper } = await render(AppLayout, { route: '/dashboard', session: {}, locale: 'en' })
    expect(linkNames(wrapper.get('[data-testid="bottom-nav"]').element)).toEqual([
      'Dashboard',
      'Transactions',
      '',
      'Budgets',
      'Settings',
    ])
  })

  it('user menu shows the name and signs out', async () => {
    const logout = vi.fn(() => new HttpResponse(null, { status: 204 }))
    const { wrapper, router, user } = await render(App, {
      route: '/dashboard',
      session: { displayName: 'Ana Souza' },
      handlers: [rest.post(`${api}/auth/logout`, logout)],
    })
    const trigger = wrapper.get('button[aria-haspopup="menu"]')
    expect(trigger.text()).toContain('Ana Souza')
    await user.click(trigger.element)
    const item = [...document.querySelectorAll('[role="menuitem"]')].find(
      (el) => el.textContent?.trim() === 'Sair',
    )
    expect(item).toBeDefined()
    await user.click(item!)
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(logout).toHaveBeenCalledTimes(1)
  })

  it('renders the page inside the app shell', async () => {
    const { wrapper } = await render(App, { route: '/budgets', session: {} })
    expect(wrapper.get('main h1').text()).toBe('Orçamentos')
  })
})
