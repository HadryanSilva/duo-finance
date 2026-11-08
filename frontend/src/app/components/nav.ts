import {
  ArrowLeftRight,
  Landmark,
  LayoutDashboard,
  PiggyBank,
  Repeat,
  Settings,
  ShieldCheck,
  Tags,
} from '@lucide/vue'
import type { Component } from 'vue'

export interface NavItem {
  to: string
  /** i18n key */
  label: string
  icon: Component
}

const item = (to: string, label: string, icon: Component): NavItem => ({ to, label, icon })

export const DASHBOARD = item('/dashboard', 'nav.dashboard', LayoutDashboard)
export const TRANSACTIONS = item('/transactions', 'nav.transactions', ArrowLeftRight)
export const BUDGETS = item('/budgets', 'nav.budgets', PiggyBank)
export const SETTINGS = item('/settings', 'nav.settings', Settings)
export const ADMIN = item('/admin', 'nav.admin', ShieldCheck)

export const SIDEBAR_ITEMS: readonly NavItem[] = [
  DASHBOARD,
  TRANSACTIONS,
  item('/bank-accounts', 'nav.bankAccounts', Landmark),
  item('/categories', 'nav.categories', Tags),
  BUDGETS,
  item('/recurring', 'nav.recurring', Repeat),
  SETTINGS,
]
