import { QueryClient } from '@tanstack/vue-query'
import { http as rest, HttpResponse, delay } from 'msw'
import { createPinia } from 'pinia'
import { describe, expect, it, vi } from 'vitest'
import { createApp, h } from 'vue'
import { createMemoryHistory } from 'vue-router'
import { installProviders, useHttp } from '@/app/providers'
import { createAppRouter } from '@/app/router'
import type { AuthResponse, MeResponse } from '@/shared/api/types'
import { i18n } from '@/shared/i18n'
import { server } from '@/test/msw'
import { render, testUser, TEST_TOKEN } from '@/test/render'
import { useSession } from './session-store'

const api = `${window.location.origin}/api/v1`
const problem = (status: number, code: string) =>
  HttpResponse.json({ status, code, title: 't', detail: 'raw' }, { status })
const auth = (token: string, user: Partial<MeResponse> = {}): AuthResponse => ({
  accessToken: token,
  expiresIn: 900,
  user: { ...testUser, ...user },
})
const Empty = { render: () => h('div') }

/** Mounts the real app wiring with a session that still has to be restored. */
async function boot(route = '/') {
  const pinia = createPinia()
  const history = createMemoryHistory()
  history.replace(route)
  const router = createAppRouter(history)
  const queryClient = new QueryClient()
  const app = createApp(Empty)
  installProviders(app, { pinia, router, queryClient })
  app.mount(document.createElement('div'))
  await router.isReady()
  return { session: useSession(pinia), router, queryClient }
}

/** Serves the refresh endpoint with the given responses in order (the last one repeats). */
function serveRefresh(...responses: (() => Response | Promise<Response>)[]) {
  const hits = { n: 0 }
  server.use(
    rest.post(`${api}/auth/refresh`, () => {
      const respond = responses[Math.min(hits.n, responses.length - 1)]!
      hits.n++
      return respond()
    }),
  )
  return hits
}

describe('session store', () => {
  it('restore_withValidCookie_authenticates', async () => {
    const hits = serveRefresh(() => HttpResponse.json(auth('fresh')))
    localStorage.setItem(`df.onboarded.${testUser.id}`, '1')
    const { session, router } = await boot('/dashboard')
    expect(session.status).toBe('authenticated')
    expect(session.accessToken).toBe('fresh')
    expect(session.user?.email).toBe(testUser.email)
    expect(router.currentRoute.value.path).toBe('/dashboard') // the guard awaited the restore
    await session.restore()
    await router.push('/transactions')
    expect(hits.n).toBe(1) // once, on the first navigation only
  })

  it('restore_withoutCookie_isAnonymous', async () => {
    serveRefresh(() => problem(401, 'REFRESH_TOKEN_INVALID'))
    const { session, router } = await boot('/dashboard')
    expect(session.status).toBe('anonymous')
    expect(session.accessToken).toBeNull()
    expect(router.currentRoute.value.fullPath).toBe('/login?redirect=/dashboard')
  })

  it('restore_whenRefreshEndpointUnreachable_isAnonymous', async () => {
    serveRefresh(() => HttpResponse.error())
    const { session } = await boot('/')
    expect(session.status).toBe('anonymous')
  })

  it('refresh_isSingleFlight', async () => {
    const hits = serveRefresh(
      () => problem(401, 'REFRESH_TOKEN_INVALID'),
      async () => {
        await delay(30)
        return HttpResponse.json(auth('t2'))
      },
    )
    const { session } = await boot('/')
    expect(hits.n).toBe(1)
    const results = await Promise.all([session.refresh(), session.refresh(), session.refresh()])
    expect(results).toEqual([true, true, true])
    expect(hits.n).toBe(2)
    expect(session.accessToken).toBe('t2')
  })

  it('refresh_raceRetriesOnce', async () => {
    const hits = serveRefresh(
      () => problem(401, 'REFRESH_RACE'),
      () => HttpResponse.json(auth('after-race')),
    )
    const { session } = await boot('/')
    expect(hits.n).toBe(2)
    expect(session.status).toBe('authenticated')
    expect(session.accessToken).toBe('after-race')
  })

  it('refresh_raceTwice_givesUpWithoutThrowing', async () => {
    const hits = serveRefresh(() => problem(401, 'REFRESH_RACE'))
    const { session } = await boot('/')
    expect(hits.n).toBe(2)
    expect(session.status).toBe('anonymous')
    await expect(session.refresh()).resolves.toBe(false)
  })

  it('switchAccount_clearsQueryCache', async () => {
    const { queryClient, pinia } = await render(Empty, { session: {} })
    const session = useSession(pinia)
    queryClient.setQueryData(['transactions'], [{ id: 'old-account-row' }])
    session.switchAccount(auth('other-account', { accountId: 'acc-2' }))
    expect(queryClient.getQueryData(['transactions'])).toBeUndefined()
    expect(session.accessToken).toBe('other-account')
    expect(session.user?.accountId).toBe('acc-2')
  })

  it('applyAuth_setsLocaleFromUser', async () => {
    const { pinia } = await render(Empty)
    useSession(pinia).applyAuth(auth('t', { locale: 'en' }))
    expect(i18n.global.locale.value).toBe('en')
    expect(document.documentElement.lang).toBe('en')
    useSession(pinia).applyAuth(auth('t', { locale: 'pt-BR' }))
    expect(i18n.global.locale.value).toBe('pt-BR')
  })

  it('isAdmin follows the user role', async () => {
    const { pinia } = await render(Empty, { session: { role: 'ADMIN' } })
    expect(useSession(pinia).isAdmin).toBe(true)
    useSession(pinia).applyAuth(auth('t', { role: 'USER' }))
    expect(useSession(pinia).isAdmin).toBe(false)
  })

  it('onUnauthenticated clears the session and cache, asks to sign in, never calls logout', async () => {
    const logout = vi.fn(() => new HttpResponse(null, { status: 204 }))
    const { pinia, router, queryClient } = await render(Empty, {
      session: {},
      route: '/transactions?month=2026-03',
      handlers: [rest.post(`${api}/auth/logout`, logout)],
    })
    const session = useSession(pinia)
    queryClient.setQueryData(['transactions'], [1])
    session.onUnauthenticated()
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/login'))
    expect(router.currentRoute.value.query.redirect).toBe('/transactions?month=2026-03')
    expect(session.accessToken).toBeNull()
    expect(session.user).toBeNull()
    expect(session.status).toBe('anonymous')
    expect(queryClient.getQueryData(['transactions'])).toBeUndefined()
    expect(logout).not.toHaveBeenCalled()
  })

  it('onUnauthenticated is a no-op when already anonymous', async () => {
    const { pinia, router } = await render(Empty, { route: '/privacy' })
    const push = vi.spyOn(router, 'push')
    useSession(pinia).onUnauthenticated()
    useSession(pinia).onUnauthenticated()
    expect(push).not.toHaveBeenCalled()
  })

  it('logout calls the backend, clears everything and goes to /login even if it fails', async () => {
    const logout = vi.fn(() => problem(500, 'INTERNAL_ERROR'))
    const { pinia, router, queryClient } = await render(Empty, {
      session: {},
      route: '/dashboard',
      handlers: [rest.post(`${api}/auth/logout`, logout)],
    })
    queryClient.setQueryData(['dashboard'], {})
    await useSession(pinia).logout()
    expect(logout).toHaveBeenCalledTimes(1)
    expect(useSession(pinia).status).toBe('anonymous')
    expect(useSession(pinia).accessToken).toBeNull()
    expect(queryClient.getQueryData(['dashboard'])).toBeUndefined()
    expect(router.currentRoute.value.fullPath).toBe('/login')
  })
})

describe('useHttp wiring', () => {
  it('sends the session token and, when the refresh fails, ends the session once', async () => {
    const seen: (string | null)[] = []
    const { pinia, router } = await render(Empty, {
      session: {},
      route: '/dashboard',
      handlers: [
        rest.get(`${api}/data`, ({ request }) => {
          seen.push(request.headers.get('authorization'))
          return problem(401, 'UNAUTHENTICATED')
        }),
        rest.post(`${api}/auth/refresh`, () => problem(401, 'REFRESH_TOKEN_INVALID')),
      ],
    })
    const http = useHttp()
    const results = await Promise.allSettled([http.get('/data'), http.get('/data')])
    expect(results.every((r) => r.status === 'rejected')).toBe(true)
    expect(seen).toEqual([`Bearer ${TEST_TOKEN}`, `Bearer ${TEST_TOKEN}`])
    expect(useSession(pinia).status).toBe('anonymous')
    await vi.waitFor(() =>
      expect(router.currentRoute.value.fullPath).toBe('/login?redirect=/dashboard'),
    )
  })

  it('refreshes with the cookie and retries under the new token', async () => {
    const { pinia } = await render(Empty, {
      session: {},
      handlers: [
        rest.get(`${api}/data`, ({ request }) =>
          request.headers.get('authorization') === 'Bearer renewed'
            ? HttpResponse.json({ ok: true })
            : problem(401, 'UNAUTHENTICATED'),
        ),
        rest.post(`${api}/auth/refresh`, () => HttpResponse.json(auth('renewed'))),
      ],
    })
    await expect(useHttp().get('/data')).resolves.toEqual({ ok: true })
    expect(useSession(pinia).accessToken).toBe('renewed')
  })

  it('a request of the previous account is not retried after switchAccount', async () => {
    let release: () => void = () => {}
    const gate = new Promise<void>((resolve) => (release = resolve))
    const seen: (string | null)[] = []
    const refresh = vi.fn(() => HttpResponse.json(auth('should-not-be-used')))
    const { pinia } = await render(Empty, {
      session: {},
      handlers: [
        rest.get(`${api}/slow`, async ({ request }) => {
          seen.push(request.headers.get('authorization'))
          await gate
          return problem(401, 'UNAUTHENTICATED')
        }),
        rest.post(`${api}/auth/refresh`, refresh),
      ],
    })
    const slow = useHttp().get('/slow')
    await vi.waitFor(() => expect(seen).toHaveLength(1))
    useSession(pinia).switchAccount(auth('account-2', { accountId: 'acc-2' }))
    release()
    await expect(slow).rejects.toMatchObject({ status: 401, code: 'SESSION_STALE' })
    expect(seen).toEqual([`Bearer ${TEST_TOKEN}`])
    expect(refresh).not.toHaveBeenCalled()
    expect(useSession(pinia).accessToken).toBe('account-2')
  })

  it('402 SUBSCRIPTION_INACTIVE goes to /suspended', async () => {
    const { router } = await render(Empty, {
      session: {},
      route: '/dashboard',
      handlers: [rest.get(`${api}/dashboard`, () => problem(402, 'SUBSCRIPTION_INACTIVE'))],
    })
    await expect(useHttp().get('/dashboard')).rejects.toMatchObject({ status: 402 })
    await vi.waitFor(() => expect(router.currentRoute.value.path).toBe('/suspended'))
  })
})
