import type { GoalResponse } from '@/shared/api/types'
import { i18n } from '@/shared/i18n'
import { formatBusinessDate, formatMonth } from '@/shared/lib/dates'
import { formatMoney, type Locale } from '@/shared/lib/money'

/**
 * The projection line of a goal: what it takes to reach the target date, or when the current
 * pace gets there; a target date already behind says so, with the pace when there is one.
 * Null when there is nothing to say (no date and no contributions yet).
 */
export function projectionText(
  goal: Pick<GoalResponse, 'targetDate' | 'projection'>,
  locale: Locale,
): string | null {
  const { t } = i18n.global
  const p = goal.projection
  if (!p) return null
  if (goal.targetDate && p.targetDatePassed) {
    const date = formatBusinessDate(goal.targetDate, locale, 'long')
    return p.projectedMonth
      ? t('goals.projection.passedPace', { date, month: formatMonth(p.projectedMonth, locale) })
      : t('goals.projection.passed', { date })
  }
  if (goal.targetDate && p.requiredMonthlyCents !== null) {
    return t('goals.projection.required', {
      date: formatBusinessDate(goal.targetDate, locale, 'long'),
      amount: formatMoney(p.requiredMonthlyCents, locale),
    })
  }
  if (p.projectedMonth) {
    return t('goals.projection.pace', { month: formatMonth(p.projectedMonth, locale) })
  }
  return null
}

/** Money in a confirmation message, which is a plain string (it cannot hold a MoneyText). */
export function moneyLabel(cents: number, locale: Locale): string {
  return formatMoney(cents, locale)
}

/** Share of the target already saved, as a whole number clamped to 0..100. */
export function goalPercent(savedCents: number, targetCents: number): number {
  if (targetCents <= 0) return 0
  return Math.min(100, Math.max(0, Math.floor((savedCents * 100) / targetCents)))
}
