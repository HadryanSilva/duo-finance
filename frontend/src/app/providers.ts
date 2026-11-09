import { VueQueryPlugin, type QueryClient } from '@tanstack/vue-query'
import type { Pinia } from 'pinia'
import type { App } from 'vue'
import type { Router } from 'vue-router'
import { createHttp, type Http } from '@/shared/api/http'
import { i18n } from '@/shared/i18n'
import { useSession } from '@/shared/session/session-store'
import { installGuards } from './guards'

export { queryClient } from './query-client'

export interface Providers {
  pinia: Pinia
  router: Router
  queryClient: QueryClient
}

/** Registers Pinia, i18n, Vue Query, the guards and the router (last: it starts navigating). */
export function installProviders(app: App, { pinia, router, queryClient }: Providers): void {
  app.use(pinia)
  app.use(i18n)
  app.use(VueQueryPlugin, { queryClient })
  installGuards(router, pinia)
  app.use(router)
}

const httpBySession = new WeakMap<object, Http>()

/** The app's single http client, wired to the session store. */
export function useHttp(): Http {
  const session = useSession()
  let http = httpBySession.get(session)
  if (!http) {
    http = createHttp({
      getToken: () => session.accessToken,
      getEpoch: () => session.getEpoch(),
      refresh: () => session.refresh(),
      onUnauthenticated: () => session.onUnauthenticated(),
      onSubscriptionInactive: () => session.onSubscriptionInactive(),
    })
    httpBySession.set(session, http)
  }
  return http
}
