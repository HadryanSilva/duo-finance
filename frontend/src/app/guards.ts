import type { Pinia } from 'pinia'
import type { Router } from 'vue-router'
import { isOnboarded } from '@/shared/session/onboarding'
import { useSession } from '@/shared/session/session-store'

const NO_ONBOARDING_REDIRECT = ['/onboarding', '/suspended']

export function installGuards(router: Router, pinia: Pinia): void {
  // The onboarding check runs on the user's first authenticated entry of this app session.
  let onboardingCheckedFor: string | null = null

  router.beforeEach(async (to) => {
    const session = useSession(pinia)
    if (session.status === 'unknown') await session.restore()
    const user = session.status === 'authenticated' ? session.user : null
    const needsAuth = !!(to.meta.requiresAuth || to.meta.requiresAdmin)

    if (needsAuth && !user) return { path: '/login', query: { redirect: to.fullPath } }
    if (user && (to.path === '/login' || to.path === '/register')) return '/dashboard'
    if (to.meta.requiresAdmin && !session.isAdmin) return '/dashboard'

    if (user && needsAuth && onboardingCheckedFor !== user.id) {
      onboardingCheckedFor = user.id
      const exempt = NO_ONBOARDING_REDIRECT.includes(to.path) || to.path.startsWith('/invite/')
      if (!exempt && !isOnboarded(user.id)) return '/onboarding'
    }
    return true
  })
}
