import { useQueryClient } from '@tanstack/vue-query'
import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import type { AuthResponse, MeResponse } from '../api/types'
import { setLocale, type Locale } from '../i18n'

export type SessionStatus = 'unknown' | 'anonymous' | 'authenticated'

const REFRESH_URL = '/api/v1/auth/refresh'
const LOGOUT_URL = '/api/v1/auth/logout'
const RACE_RETRY_MS = 300

const LOCALES: readonly Locale[] = ['pt-BR', 'en']
const asLocale = (l: string) => LOCALES.find((x) => x === l)

function post(url: string): Promise<Response> {
  return fetch(url, { method: 'POST', credentials: 'same-origin' })
}

async function isRefreshRace(res: Response): Promise<boolean> {
  if (res.status !== 401) return false
  try {
    const body: unknown = await res.clone().json()
    return !!body && typeof body === 'object' && 'code' in body && body.code === 'REFRESH_RACE'
  } catch {
    return false
  }
}

/**
 * The session: access token in memory only, refresh token in the HttpOnly cookie.
 * Needs the app's router and VueQueryPlugin installed (it injects both).
 */
export const useSession = defineStore('session', () => {
  const router = useRouter()
  const queryClient = useQueryClient()

  const accessToken = ref<string | null>(null)
  const user = ref<MeResponse | null>(null)
  const status = ref<SessionStatus>('unknown')
  const isAdmin = computed(() => user.value?.role === 'ADMIN')

  // Bumped whenever the session is replaced or cleared; http never retries an older epoch's request.
  let epoch = 0
  const getEpoch = () => epoch

  function applyAuth(r: AuthResponse): void {
    accessToken.value = r.accessToken
    user.value = r.user
    status.value = 'authenticated'
    const locale = asLocale(r.user.locale)
    if (locale) setLocale(locale)
  }

  /** Tokens of another Account replace the current ones; nothing cached from the old one survives. */
  function switchAccount(r: AuthResponse): void {
    epoch++
    queryClient.clear()
    applyAuth(r)
  }

  function clear(): void {
    epoch++
    accessToken.value = null
    user.value = null
    status.value = 'anonymous'
    queryClient.clear()
  }

  async function refreshOnce(): Promise<boolean> {
    const startedIn = epoch
    try {
      let res = await post(REFRESH_URL)
      if (await isRefreshRace(res)) {
        // Another tab rotated the cookie a moment ago: the new one is already in the jar.
        await new Promise((resolve) => setTimeout(resolve, RACE_RETRY_MS))
        res = await post(REFRESH_URL)
      }
      if (!res.ok) return false
      const auth = (await res.json()) as AuthResponse
      // The session was switched or cleared meanwhile: keep what is in place now.
      if (epoch !== startedIn) return accessToken.value !== null
      applyAuth(auth)
      return true
    } catch {
      return false
    }
  }

  let refreshing: Promise<boolean> | null = null
  /** Single-flight: restore() and the http client share one refresh. Never throws. */
  function refresh(): Promise<boolean> {
    refreshing ??= refreshOnce().finally(() => {
      refreshing = null
    })
    return refreshing
  }

  let restoring: Promise<void> | null = null
  /** Restores the session from the refresh cookie, once: status leaves 'unknown'. */
  function restore(): Promise<void> {
    if (status.value !== 'unknown') return Promise.resolve()
    restoring ??= refresh().then((ok) => {
      if (!ok && status.value === 'unknown') status.value = 'anonymous'
    })
    return restoring
  }

  /** http's failed-refresh handler: clears the session (no backend logout) and asks to sign in. */
  function onUnauthenticated(): void {
    if (status.value === 'anonymous') return
    const from = router.currentRoute.value
    clear()
    if (from.path !== '/login') {
      void router.push({ path: '/login', query: { redirect: from.fullPath } })
    }
  }

  function onSubscriptionInactive(): void {
    if (router.currentRoute.value.path !== '/suspended') void router.push('/suspended')
  }

  async function logout(): Promise<void> {
    try {
      await post(LOGOUT_URL)
    } catch {
      // the local session ends regardless
    }
    clear()
    await router.push('/login')
  }

  return {
    accessToken,
    user,
    status,
    isAdmin,
    getEpoch,
    applyAuth,
    switchAccount,
    refresh,
    restore,
    logout,
    onUnauthenticated,
    onSubscriptionInactive,
  }
})
