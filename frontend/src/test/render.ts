import { QueryClient } from '@tanstack/vue-query'
import userEvent from '@testing-library/user-event'
import { mount, type ComponentMountingOptions } from '@vue/test-utils'
import type { RequestHandler } from 'msw'
import { createPinia } from 'pinia'
import type { Component } from 'vue'
import { createMemoryHistory } from 'vue-router'
import { installProviders } from '@/app/providers'
import { createAppRouter } from '@/app/router'
import type { MeResponse } from '@/shared/api/types'
import { setLocale, type Locale } from '@/shared/i18n'
import { markOnboarded } from '@/shared/session/onboarding'
import { server } from './msw'

const mounted: { unmount: () => void; host: HTMLElement }[] = []

/** Unmounts everything render() mounted; called after each test by setup.ts. */
export function cleanup(): void {
  for (const { unmount, host } of mounted.splice(0)) {
    unmount()
    host.remove()
  }
  document.body.innerHTML = '' // teleported content (dialogs, menus)
}

export const TEST_TOKEN = 'test-access-token'

export const testUser: MeResponse = {
  id: '00000000-0000-4000-8000-000000000001',
  email: 'ana@example.com',
  displayName: 'Ana Souza',
  locale: 'pt-BR',
  emailVerified: true,
  role: 'USER',
  accountId: '00000000-0000-4000-8000-0000000000a1',
}

export interface RenderOptions {
  /** Initial location, guards included (default '/'). */
  route?: string
  /** Signed-in user (merged over `testUser`); `null` or omitted renders anonymous. */
  session?: Partial<MeResponse> | null
  /** MSW handlers for this test (reset after each test). */
  handlers?: RequestHandler[]
  locale?: Locale
  /** Whether the signed-in user already went through onboarding (default true). */
  onboarded?: boolean
}

/**
 * Mounts a component with a fresh Pinia, the app routes on a memory history, i18n, and
 * a new QueryClient (no retries). Resolves after the first navigation settled.
 * `user` is @testing-library/user-event bound to the document.
 */
export async function render<C extends Component>(
  component: C,
  opts: RenderOptions = {},
  mountOptions: ComponentMountingOptions<C> = {},
) {
  const { route = '/', session = null, handlers = [], locale = 'pt-BR', onboarded = true } = opts
  if (handlers.length) server.use(...handlers)
  setLocale(locale)

  const pinia = createPinia()
  if (session) {
    const user = { ...testUser, ...session }
    // Hydrates the setup store's state when it is created.
    pinia.state.value.session = { accessToken: TEST_TOKEN, user, status: 'authenticated' }
    if (onboarded) markOnboarded(user.id)
  } else {
    pinia.state.value.session = { accessToken: null, user: null, status: 'anonymous' }
  }

  const history = createMemoryHistory()
  history.replace(route) // the router starts here when installed
  const router = createAppRouter(history)
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false }, mutations: { retry: false } },
  })

  const host = document.createElement('div')
  document.body.appendChild(host)
  const wrapper = mount(component, {
    ...mountOptions,
    attachTo: host,
    global: {
      ...mountOptions.global,
      plugins: [
        ...(mountOptions.global?.plugins ?? []),
        { install: (app) => installProviders(app, { pinia, router, queryClient }) },
      ],
    },
  } as ComponentMountingOptions<C>)
  mounted.push({ unmount: () => wrapper.unmount(), host })
  await router.isReady()

  return { wrapper, router, queryClient, pinia, user: userEvent.setup() }
}
