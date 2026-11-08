import { http as rest, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { server } from '@/test/msw'
import { createHttp } from './http'
import { ApiError, isApiError } from './problem'

const base = `${window.location.origin}/api/v1`

const problem = (status: number, code: string, extra: object = {}) =>
  HttpResponse.json({ status, code, title: 't', detail: 'raw', ...extra }, { status })

/** Serves 200 only for the refreshed token. */
const guarded = () =>
  rest.get(`${base}/data`, ({ request }) =>
    request.headers.get('authorization') === 'Bearer new'
      ? HttpResponse.json({ ok: true })
      : problem(401, 'UNAUTHENTICATED'),
  )

function setup(over: Partial<Parameters<typeof createHttp>[0]> = {}) {
  let token: string | null = 'old'
  const deps = {
    getToken: () => token,
    refresh: vi.fn(async () => {
      token = 'new'
      return true
    }),
    onUnauthenticated: vi.fn(),
    onSubscriptionInactive: vi.fn(),
    ...over,
  }
  return { http: createHttp(deps), deps }
}

describe('http client', () => {
  it('sends bearer token and JSON body, under /api/v1', async () => {
    let seen: { auth: string | null; ct: string | null; body: unknown } | undefined
    server.use(
      rest.post(`${base}/things`, async ({ request }) => {
        seen = {
          auth: request.headers.get('authorization'),
          ct: request.headers.get('content-type'),
          body: await request.json(),
        }
        return HttpResponse.json({ id: 1 })
      }),
    )
    const { http } = setup()
    expect(await http.post('/things', { a: 1 })).toEqual({ id: 1 })
    expect(seen).toEqual({ auth: 'Bearer old', ct: 'application/json', body: { a: 1 } })
  })

  it('omits Authorization without a token and omits undefined query values', async () => {
    let seen: { auth: string | null; search: string } | undefined
    server.use(
      rest.get(`${base}/list`, ({ request }) => {
        seen = { auth: request.headers.get('authorization'), search: new URL(request.url).search }
        return HttpResponse.json([])
      }),
    )
    const { http } = setup({ getToken: () => null })
    await http.get('/list', { month: '2026-03', page: 0, q: undefined })
    expect(seen).toEqual({ auth: null, search: '?month=2026-03&page=0' })
  })

  it('204 resolves undefined', async () => {
    server.use(rest.delete(`${base}/x`, () => new HttpResponse(null, { status: 204 })))
    const { http } = setup()
    await expect(http.del('/x')).resolves.toBeUndefined()
  })

  it('put and patch send their methods and bodies', async () => {
    const seen: string[] = []
    server.use(
      rest.put(`${base}/x`, async ({ request }) => {
        seen.push(`put ${JSON.stringify(await request.json())}`)
        return HttpResponse.json({})
      }),
      rest.patch(`${base}/x`, async ({ request }) => {
        seen.push(`patch ${JSON.stringify(await request.json())}`)
        return HttpResponse.json({})
      }),
    )
    const { http } = setup()
    await http.put('/x', { a: 1 })
    await http.patch('/x', { b: 2 })
    expect(seen).toEqual(['put {"a":1}', 'patch {"b":2}'])
  })

  it('concurrent401s_triggerSingleRefresh', async () => {
    server.use(guarded())
    const { http, deps } = setup()
    const res = await Promise.all([http.get('/data'), http.get('/data'), http.get('/data')])
    expect(res).toEqual([{ ok: true }, { ok: true }, { ok: true }])
    expect(deps.refresh).toHaveBeenCalledTimes(1)
    expect(deps.onUnauthenticated).not.toHaveBeenCalled()
  })

  it('refreshRace_retriesOnceWithoutLogout', async () => {
    server.use(guarded())
    let refreshHits = 0
    server.use(
      rest.post(`${base}/auth/refresh`, () => {
        refreshHits++
        return refreshHits === 1
          ? problem(401, 'REFRESH_RACE')
          : HttpResponse.json({ accessToken: 'new' })
      }),
    )
    let token = 'old'
    // Mirrors the session refresh(): on REFRESH_RACE wait 300 ms and try once more.
    const callRefresh = async () => {
      const res = await fetch(`${base}/auth/refresh`, { method: 'POST' })
      return { res, body: (await res.json()) as { code?: string; accessToken?: string } }
    }
    const refresh = vi.fn(async () => {
      let r = await callRefresh()
      if (r.res.status === 401 && r.body.code === 'REFRESH_RACE') {
        await new Promise((resolve) => setTimeout(resolve, 300))
        r = await callRefresh()
      }
      if (!r.res.ok || !r.body.accessToken) return false
      token = r.body.accessToken
      return true
    })
    const onUnauthenticated = vi.fn()
    const http = createHttp({
      getToken: () => token,
      refresh,
      onUnauthenticated,
      onSubscriptionInactive: vi.fn(),
    })
    await expect(http.get('/data')).resolves.toEqual({ ok: true })
    expect(refreshHits).toBe(2)
    expect(refresh).toHaveBeenCalledTimes(1)
    expect(onUnauthenticated).not.toHaveBeenCalled()
  })

  it('a 401 landing after the refresh settled reuses the new token without refreshing again', async () => {
    let release: () => void = () => {}
    const gate = new Promise<void>((resolve) => (release = resolve))
    server.use(
      guarded(),
      rest.get(`${base}/slow`, async ({ request }) => {
        const auth = request.headers.get('authorization')
        if (auth === 'Bearer new') return HttpResponse.json({ slow: true })
        await gate // answer 401 only after the other request's refresh is done
        return problem(401, 'UNAUTHENTICATED')
      }),
    )
    const { http, deps } = setup()
    const slow = http.get('/slow')
    await expect(http.get('/data')).resolves.toEqual({ ok: true })
    expect(deps.refresh).toHaveBeenCalledTimes(1)
    release()
    await expect(slow).resolves.toEqual({ slow: true })
    expect(deps.refresh).toHaveBeenCalledTimes(1)
  })

  it('a rejecting refresh is a failed refresh: ApiError for callers, one logout', async () => {
    server.use(rest.get(`${base}/data`, () => problem(401, 'UNAUTHENTICATED')))
    const { http, deps } = setup({
      refresh: vi.fn(async () => {
        throw new TypeError('boom')
      }),
    })
    const results = await Promise.allSettled([http.get('/data'), http.get('/data')])
    for (const r of results) {
      expect(r.status).toBe('rejected')
      expect((r as PromiseRejectedResult).reason).toBeInstanceOf(ApiError)
      expect((r as PromiseRejectedResult).reason).toMatchObject({ status: 401 })
    }
    expect(deps.onUnauthenticated).toHaveBeenCalledTimes(1)
  })

  it('onUnauthenticated fires once per failed burst, not per waiting request', async () => {
    server.use(rest.get(`${base}/data`, () => problem(401, 'UNAUTHENTICATED')))
    const { http, deps } = setup({ refresh: vi.fn(async () => false) })
    const results = await Promise.allSettled([
      http.get('/data'),
      http.get('/data'),
      http.get('/data'),
    ])
    expect(results.every((r) => r.status === 'rejected')).toBe(true)
    expect(deps.refresh).toHaveBeenCalledTimes(1)
    expect(deps.onUnauthenticated).toHaveBeenCalledTimes(1)
  })

  it('retries a request at most once', async () => {
    let hits = 0
    server.use(rest.get(`${base}/data`, () => (hits++, problem(401, 'UNAUTHENTICATED'))))
    const { http, deps } = setup()
    await expect(http.get('/data')).rejects.toMatchObject({ status: 401 })
    expect(hits).toBe(2)
    expect(deps.refresh).toHaveBeenCalledTimes(1)
  })

  it('a later burst starts a new refresh', async () => {
    server.use(guarded())
    let token = 'old'
    const refresh = vi.fn(async () => ((token = 'new'), true))
    const http = createHttp({
      getToken: () => token,
      refresh,
      onUnauthenticated: vi.fn(),
      onSubscriptionInactive: vi.fn(),
    })
    await http.get('/data')
    token = 'old'
    await http.get('/data')
    expect(refresh).toHaveBeenCalledTimes(2)
  })

  it('failedRefresh_callsOnUnauthenticated', async () => {
    server.use(rest.get(`${base}/data`, () => problem(401, 'UNAUTHENTICATED')))
    const { http, deps } = setup({ refresh: vi.fn(async () => false) })
    await expect(http.get('/data')).rejects.toMatchObject({ code: 'UNAUTHENTICATED', status: 401 })
    expect(deps.onUnauthenticated).toHaveBeenCalledTimes(1)
  })

  it('authEndpoints_doNotTriggerRefresh', async () => {
    server.use(
      rest.post(`${base}/auth/login`, () => problem(401, 'INVALID_CREDENTIALS')),
      rest.post(`${base}/auth/register`, () => problem(401, 'UNAUTHENTICATED')),
      rest.post(`${base}/auth/refresh`, () => problem(401, 'REFRESH_TOKEN_INVALID')),
    )
    const { http, deps } = setup()
    await expect(http.post('/auth/login', {})).rejects.toMatchObject({
      code: 'INVALID_CREDENTIALS',
    })
    await expect(http.post('/auth/register', {})).rejects.toBeInstanceOf(ApiError)
    await expect(http.post('/auth/refresh')).rejects.toMatchObject({
      code: 'REFRESH_TOKEN_INVALID',
    })
    expect(deps.refresh).not.toHaveBeenCalled()
    expect(deps.onUnauthenticated).not.toHaveBeenCalled()
  })

  it('subscriptionInactive_callsHandler', async () => {
    server.use(rest.get(`${base}/data`, () => problem(402, 'SUBSCRIPTION_INACTIVE')))
    const { http, deps } = setup()
    await expect(http.get('/data')).rejects.toMatchObject({
      code: 'SUBSCRIPTION_INACTIVE',
      status: 402,
    })
    expect(deps.onSubscriptionInactive).toHaveBeenCalledTimes(1)
  })

  it('mapsProblemDetailToApiError', async () => {
    server.use(
      rest.post(`${base}/x`, () =>
        problem(400, 'VALIDATION_FAILED', { errors: [{ field: 'name', code: 'Size' }] }),
      ),
    )
    const { http } = setup()
    const err = await http.post('/x', {}).catch((e: unknown) => e)
    expect(isApiError(err)).toBe(true)
    expect(err).toMatchObject({
      status: 400,
      code: 'VALIDATION_FAILED',
      fieldErrors: [{ field: 'name', code: 'Size' }],
    })
  })

  it('network failure becomes NETWORK_ERROR with status 0', async () => {
    server.use(rest.get(`${base}/data`, () => HttpResponse.error()))
    const { http } = setup()
    await expect(http.get('/data')).rejects.toMatchObject({ code: 'NETWORK_ERROR', status: 0 })
  })

  it('non-JSON error body becomes UNKNOWN', async () => {
    server.use(
      rest.get(`${base}/data`, () => new HttpResponse('<html>bad gateway</html>', { status: 502 })),
    )
    const { http } = setup()
    await expect(http.get('/data')).rejects.toMatchObject({ code: 'UNKNOWN', status: 502 })
  })
})

describe('msw setup', () => {
  it('fails requests that have no handler instead of passing them through', async () => {
    const { http } = setup()
    await expect(http.get('/nothing-registered')).rejects.toMatchObject({ code: 'NETWORK_ERROR' })
  })
})
