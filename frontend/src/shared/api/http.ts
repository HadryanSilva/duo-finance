import { ApiError, type FieldError } from './problem'

export interface HttpDeps {
  getToken(): string | null
  /**
   * Session epoch, bumped whenever the session is replaced or cleared (switchAccount, logout,
   * onUnauthenticated). A 401 on a request sent in an older epoch is never retried: it fails
   * with `401 SESSION_STALE`, so a request of the previous account never runs under the new
   * account's token. Defaults to a constant epoch.
   */
  getEpoch?(): number
  /** Resolves true when a new access token is in place. */
  refresh(): Promise<boolean>
  /**
   * Called once per failed refresh burst. Contract: the handler must move the token off the one
   * that failed (clear it). Late 401s of requests sent with the failed token are rejected
   * without refreshing again, so a token left in place would never be able to refresh.
   */
  onUnauthenticated(): void
  onSubscriptionInactive(): void
}

type Query = Record<string, string | number | undefined>

/**
 * `T` is the response type, `B` the request body type: callers name the generated schema type
 * (`http.post<AuthResponse, LoginRequest>(...)`) so a hand-written body cannot drift from the API.
 */
export interface Http {
  get<T>(path: string, query?: Query): Promise<T>
  post<T, B = unknown>(path: string, body?: B): Promise<T>
  put<T, B = unknown>(path: string, body: B): Promise<T>
  patch<T, B = unknown>(path: string, body: B): Promise<T>
  del<B = unknown>(path: string, body?: B): Promise<void>
}

const NO_REFRESH = ['/auth/login', '/auth/register', '/auth/refresh']

/**
 * Endpoints that check the typed password and answer a wrong one with 401 INVALID_CREDENTIALS.
 * That 401 says nothing about the session, so it must not refresh or log out; any other 401 on
 * them (an expired token) refreshes as usual.
 */
const PASSWORD_CHECKS = ['PUT /me/password', 'DELETE /me']

async function isInvalidCredentials(res: Response): Promise<boolean> {
  try {
    const p: unknown = await res.clone().json()
    return !!p && typeof p === 'object' && 'code' in p && p.code === 'INVALID_CREDENTIALS'
  } catch {
    return false
  }
}

async function toApiError(res: Response): Promise<ApiError> {
  try {
    const p: unknown = await res.json()
    if (p && typeof p === 'object' && 'code' in p && typeof p.code === 'string') {
      const errors = 'errors' in p && Array.isArray(p.errors) ? (p.errors as FieldError[]) : []
      return new ApiError({
        status: res.status,
        code: p.code as ApiError['code'],
        fieldErrors: errors.map(({ field, code }) => ({ field, code })),
      })
    }
  } catch {
    // not JSON
  }
  return new ApiError({ status: res.status, code: 'UNKNOWN' })
}

const stale = () => new ApiError({ status: 401, code: 'SESSION_STALE' })

export function createHttp(deps: HttpDeps): Http {
  const epoch = () => deps.getEpoch?.() ?? 0
  let inflight: Promise<boolean> | null = null
  // Token the requests of the last failed burst were sent with: their late 401s must not refresh again.
  let failedFrom: { token: string | null } | null = null

  // One refresh per burst: concurrent 401s await the same promise.
  function sharedRefresh(triggeredBy: string | null): Promise<boolean> {
    inflight ??= (async () => {
      const ok = await deps.refresh().catch(() => false)
      if (ok) {
        failedFrom = null
      } else {
        failedFrom = { token: triggeredBy }
        deps.onUnauthenticated() // once per failed burst
      }
      return ok
    })().finally(() => {
      inflight = null
    })
    return inflight
  }

  async function send(
    method: string,
    path: string,
    query?: Query,
    body?: unknown,
  ): Promise<{ res: Response; token: string | null; epoch: number }> {
    const url = new URL(`/api/v1${path}`, window.location.origin)
    for (const [k, v] of Object.entries(query ?? {})) {
      if (v !== undefined) url.searchParams.set(k, String(v))
    }
    const headers: Record<string, string> = {}
    const token = deps.getToken()
    const sentIn = epoch()
    if (token) headers.Authorization = `Bearer ${token}`
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    try {
      const res = await fetch(url, {
        method,
        headers,
        credentials: 'same-origin',
        body: body === undefined ? undefined : JSON.stringify(body),
      })
      return { res, token, epoch: sentIn }
    } catch {
      throw new ApiError({ status: 0, code: 'NETWORK_ERROR' })
    }
  }

  async function request<T>(
    method: string,
    path: string,
    query?: Query,
    body?: unknown,
  ): Promise<T> {
    const first = await send(method, path, query, body)
    let res = first.res
    if (
      res.status === 401 &&
      !NO_REFRESH.includes(path) &&
      !(PASSWORD_CHECKS.includes(`${method} ${path}`) && (await isInvalidCredentials(res)))
    ) {
      if (epoch() !== first.epoch) throw stale()
      // A newer token means another request already refreshed after this one was sent.
      const current = deps.getToken()
      const alreadyRefreshed = current !== null && current !== first.token
      const lateFailure =
        failedFrom !== null && failedFrom.token === first.token && !alreadyRefreshed
      if (lateFailure) throw await toApiError(res)
      if (!alreadyRefreshed && !(await sharedRefresh(first.token))) throw await toApiError(res)
      // The session was replaced while this request waited: never retry it under the new one.
      if (epoch() !== first.epoch) throw stale()
      res = (await send(method, path, query, body)).res
    }
    if (!res.ok) {
      const err = await toApiError(res)
      if (res.status === 402 && err.code === 'SUBSCRIPTION_INACTIVE') deps.onSubscriptionInactive()
      throw err
    }
    // 204, or a 202 without a body: nothing to parse.
    const text = res.status === 204 ? '' : await res.text()
    return (text ? JSON.parse(text) : undefined) as T
  }

  return {
    get: (path, query) => request('GET', path, query),
    post: (path, body) => request('POST', path, undefined, body),
    put: (path, body) => request('PUT', path, undefined, body),
    patch: (path, body) => request('PATCH', path, undefined, body),
    del: (path, body) => request('DELETE', path, undefined, body),
  }
}
