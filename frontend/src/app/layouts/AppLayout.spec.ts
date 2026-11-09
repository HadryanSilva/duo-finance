import { http as rest, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '@/app/App.vue'
import { cleanup, render } from '@/test/render'
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

  describe('user menu "More" links (the only path to these pages below md)', () => {
    const menuItems = () =>
      [...document.querySelectorAll('[role="menuitem"]')].map((el) => el.textContent?.trim())
    const empty = () => HttpResponse.json([])
    const listHandlers = [
      rest.get(`${api}/bank-accounts`, empty),
      rest.get(`${api}/categories`, empty),
      rest.get(`${api}/recurring-rules`, empty),
      rest.get(`${api}/admin/waitlist`, () =>
        HttpResponse.json({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 }),
      ),
      // the dashboard's own data is irrelevant here
      rest.get(`${api}/*`, () => new HttpResponse(null, { status: 503 })),
    ]

    afterEach(() => {
      window.innerWidth = 1024 // jsdom's default
    })

    async function openMenu(role: 'USER' | 'ADMIN') {
      window.innerWidth = 375 // phone width: the sidebar is hidden, only the bottom nav shows
      const r = await render(App, {
        route: '/dashboard',
        session: { role },
        handlers: listHandlers,
      })
      const trigger = r.wrapper.get('button[aria-haspopup="menu"]')
      expect(trigger.element.closest('[data-testid="sidebar"]')).toBeNull()
      await r.user.click(trigger.element)
      await vi.waitFor(() => expect(menuItems()).toContain('Sair'))
      return r
    }

    it.each([
      ['Contas', '/bank-accounts'],
      ['Categorias', '/categories'],
      ['Recorrências', '/recurring'],
    ])('reaches %s from the user menu', async (name, path) => {
      const r = await openMenu('USER')
      const item = [...document.querySelectorAll('[role="menuitem"]')].find(
        (el) => el.textContent?.trim() === name,
      )
      expect(item).toBeDefined()
      await r.user.click(item!)
      await vi.waitFor(() => expect(r.router.currentRoute.value.path).toBe(path))
    })

    it('lists Admin only for ADMIN', async () => {
      await openMenu('USER')
      expect(menuItems()).toEqual(['Contas', 'Categorias', 'Recorrências', 'Sair'])
      cleanup()

      const r = await openMenu('ADMIN')
      expect(menuItems()).toEqual(['Contas', 'Categorias', 'Recorrências', 'Admin', 'Sair'])
      const admin = [...document.querySelectorAll('[role="menuitem"]')].find(
        (el) => el.textContent?.trim() === 'Admin',
      )
      await r.user.click(admin!)
      await vi.waitFor(() => expect(r.router.currentRoute.value.path).toBe('/admin'))
    })

    it('labels the section in English', async () => {
      const r = await render(App, {
        route: '/dashboard',
        session: {},
        locale: 'en',
        handlers: listHandlers,
      })
      await r.user.click(r.wrapper.get('button[aria-haspopup="menu"]').element)
      await vi.waitFor(() =>
        expect(menuItems()).toEqual(['Accounts', 'Categories', 'Recurring', 'Sign out']),
      )
      expect(document.body.textContent).toContain('More')
    })
  })

  it('renders the page inside the app shell', async () => {
    const { wrapper } = await render(App, { route: '/budgets', session: {} })
    expect(wrapper.get('main h1').text()).toBe('Orçamentos')
  })
})
