import {
  createRouter,
  createWebHistory,
  type RouteRecordRaw,
  type Router,
  type RouterHistory,
} from 'vue-router'
import NotFoundPage from './pages/NotFoundPage.vue'

export type LayoutName = 'public' | 'auth' | 'app'

declare module 'vue-router' {
  interface RouteMeta {
    public?: boolean
    /** Signed-in users are sent to the dashboard instead of seeing this page. */
    redirectAuthenticated?: boolean
    requiresAuth?: boolean
    requiresAdmin?: boolean
    /** Shell the page renders in (App.vue); defaults to 'public'. */
    layout?: LayoutName
  }
}

const pub = (layout: LayoutName) => ({ public: true, layout })
const app = { requiresAuth: true, layout: 'app' } as const

export const routes: RouteRecordRaw[] = [
  // public
  {
    path: '/',
    component: () => import('@/features/landing/pages/LandingPage.vue'),
    meta: { ...pub('public'), redirectAuthenticated: true },
  },
  {
    path: '/privacy',
    component: () => import('@/features/landing/pages/PrivacyPage.vue'),
    meta: pub('public'),
  },
  {
    path: '/terms',
    component: () => import('@/features/landing/pages/TermsPage.vue'),
    meta: pub('public'),
  },
  {
    path: '/login',
    component: () => import('@/features/auth/pages/LoginPage.vue'),
    meta: pub('auth'),
  },
  {
    path: '/register',
    component: () => import('@/features/auth/pages/RegisterPage.vue'),
    meta: pub('auth'),
  },
  {
    path: '/verify-email',
    component: () => import('@/features/auth/pages/VerifyEmailPage.vue'),
    meta: pub('auth'),
  },
  {
    path: '/forgot-password',
    component: () => import('@/features/auth/pages/ForgotPasswordPage.vue'),
    meta: pub('auth'),
  },
  {
    path: '/reset-password',
    component: () => import('@/features/auth/pages/ResetPasswordPage.vue'),
    meta: pub('auth'),
  },
  {
    path: '/invite/:token',
    component: () => import('@/features/auth/pages/AcceptInvitePage.vue'),
    meta: pub('auth'),
  },
  // authenticated
  {
    path: '/onboarding',
    component: () => import('@/features/onboarding/pages/OnboardingPage.vue'),
    meta: { requiresAuth: true, layout: 'auth' },
  },
  {
    path: '/suspended',
    component: () => import('./pages/SuspendedPage.vue'),
    meta: { requiresAuth: true, layout: 'auth' },
  },
  {
    path: '/dashboard',
    component: () => import('@/features/dashboard/pages/DashboardPage.vue'),
    meta: app,
  },
  {
    path: '/transactions',
    component: () => import('@/features/transactions/pages/TransactionsPage.vue'),
    meta: app,
  },
  {
    path: '/bank-accounts',
    component: () => import('@/features/bank-accounts/pages/BankAccountsPage.vue'),
    meta: app,
  },
  {
    path: '/categories',
    component: () => import('@/features/categories/pages/CategoriesPage.vue'),
    meta: app,
  },
  {
    path: '/budgets',
    component: () => import('@/features/budgets/pages/BudgetsPage.vue'),
    meta: app,
  },
  {
    path: '/recurring',
    component: () => import('@/features/recurring/pages/RecurringPage.vue'),
    meta: app,
  },
  {
    path: '/settings',
    component: () => import('@/features/settings/pages/SettingsPage.vue'),
    meta: app,
  },
  // admin (implies an authenticated session)
  {
    path: '/admin',
    component: () => import('@/features/admin/pages/AdminPage.vue'),
    meta: { ...app, requiresAdmin: true },
  },
  { path: '/:pathMatch(.*)*', component: NotFoundPage, meta: { layout: 'public' } },
]

export function createAppRouter(history: RouterHistory = createWebHistory()): Router {
  return createRouter({ history, routes, scrollBehavior: () => ({ top: 0 }) })
}
