import { screen, waitFor } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { render, renderRoute } from '@/test/render'
import GoogleButton from './GoogleButton.vue'

const leaveApp = vi.hoisted(() => vi.fn())
vi.mock('./leave-app', () => ({ leaveApp }))

const api = `${window.location.origin}/api/v1`

describe('GoogleButton', () => {
  beforeEach(() => {
    leaveApp.mockClear()
    sessionStorage.clear()
  })

  it('loads nothing from Google until clicked', async () => {
    await render(GoogleButton, {}, { props: { clientId: 'cid', returnTo: '/dashboard' } })
    expect(screen.getByRole('button', { name: 'Continuar com o Google' })).toBeTruthy()
    expect(document.querySelector('script[src*="google"], iframe')).toBeNull()
    expect(leaveApp).not.toHaveBeenCalled()
  })

  it('a click sends the browser to Google for this client', async () => {
    const r = await render(GoogleButton, {}, { props: { clientId: 'cid', returnTo: '/dashboard' } })
    await r.user.click(screen.getByRole('button', { name: 'Continuar com o Google' }))
    const url = new URL(leaveApp.mock.calls[0]![0] as string)
    expect(url.host).toBe('accounts.google.com')
    expect(url.searchParams.get('client_id')).toBe('cid')
  })

  it('is shown on the register page when googleClientId is set', async () => {
    await renderRoute({
      route: '/register',
      handlers: [
        http.get(`${api}/public/registration-mode`, () =>
          HttpResponse.json({ mode: 'OPEN', googleClientId: 'cid' }),
        ),
      ],
    })
    await waitFor(() =>
      expect(screen.getByRole('button', { name: 'Continuar com o Google' })).toBeTruthy(),
    )
  })
})
