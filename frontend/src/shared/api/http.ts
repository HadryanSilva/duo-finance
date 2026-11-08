import { ApiError, type FieldError } from './problem'

export interface HttpDeps {
  getToken(): string | null
  /** Resolves true when a new access token is in place. */
  refresh(): Promise<boolean>
  onUnauthenticated(): void
  onSubscriptionInactive(): void
}

type Query = Record<string, string | number | undefined>

export interface Http {
  get<T>(path: string, query?: Query): Promise<T>
  post<T>(path: string, body?: unknown): Promise<T>
  put<T>(path: string, body: unknown): Promise<T>
  patch<T>(path: string, body: unknown): Promise<T>
  del(path: string, body?: unknown): Promise<void>
}

const NO_REFRESH = ['/auth/login', '/auth/register', '/auth/refresh']

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

export function createHttp(deps: HttpDeps): Http {
  let inflight: Promise<boolean> | null = null

  // One refresh per burst: concurrent 401s await the same promise.
  function sharedRefresh(): Promise<boolean> {
    inflight ??= deps.refresh().finally(() => {
      inflight = null
    })
    return inflight
  }

  async function send(
    method: string,
    path: string,
    query?: Query,
    body?: unknown,
  ): Promise<Response> {
    const url = new URL(`/api/v1${path}`, window.location.origin)
    for (const [k, v] of Object.entries(query ?? {})) {
      if (v !== undefined) url.searchParams.set(k, String(v))
    }
    const headers: Record<string, string> = {}
    const token = deps.getToken()
    if (token) headers.Authorization = `Bearer ${token}`
    if (body !== undefined) headers['Content-Type'] = 'application/json'
    try {
      return await fetch(url, {
        method,
        headers,
        credentials: 'same-origin',
        body: body === undefined ? undefined : JSON.stringify(body),
      })
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
    let res = await send(method, path, query, body)
    if (res.status === 401 && !NO_REFRESH.includes(path)) {
      if (await sharedRefresh()) {
        res = await send(method, path, query, body)
      } else {
        const err = await toApiError(res)
        deps.onUnauthenticated()
        throw err
      }
    }
    if (!res.ok) {
      const err = await toApiError(res)
      if (res.status === 402 && err.code === 'SUBSCRIPTION_INACTIVE') deps.onSubscriptionInactive()
      throw err
    }
    if (res.status === 204) return undefined as T
    return (await res.json()) as T
  }

  return {
    get: (path, query) => request('GET', path, query),
    post: (path, body) => request('POST', path, undefined, body),
    put: (path, body) => request('PUT', path, undefined, body),
    patch: (path, body) => request('PATCH', path, undefined, body),
    del: (path, body) => request('DELETE', path, undefined, body),
  }
}
