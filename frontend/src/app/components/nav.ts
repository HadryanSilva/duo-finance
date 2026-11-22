import {
  ArrowLeftRight,
  CalendarCheck,
  Landmark,
  LayoutDashboard,
  PiggyBank,
  Repeat,
  Settings,
  ShieldCheck,
  Tags,
  Target,
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
/** A closed month against the average of the months before it. */
export const REVIEW = item('/review', 'nav.review', CalendarCheck)
export const TRANSACTIONS = item('/transactions', 'nav.transactions', ArrowLeftRight)
export const BUDGETS = item('/budgets', 'nav.budgets', PiggyBank)
export const SETTINGS = item('/settings', 'nav.settings', Settings)
export const ADMIN = item('/admin', 'nav.admin', ShieldCheck)
const BANK_ACCOUNTS = item('/bank-accounts', 'nav.bankAccounts', Landmark)
const CATEGORIES = item('/categories', 'nav.categories', Tags)
export const GOALS = item('/goals', 'nav.goals', Target)
const RECURRING = item('/recurring', 'nav.recurring', Repeat)

/** Pages used week to week, first in the sidebar. */
export const DAILY_ITEMS: readonly NavItem[] = [DASHBOARD, REVIEW, TRANSACTIONS, BUDGETS, GOALS]

/** Pages configured once ("Cadastros"), grouped under their own heading. */
export const SETUP_ITEMS: readonly NavItem[] = [BANK_ACCOUNTS, CATEGORIES, RECURRING]

/** The daily pages the bottom nav has no room for, offered in the user menu with SETUP_ITEMS. */
export const MORE_ITEMS: readonly NavItem[] = [REVIEW, GOALS]
