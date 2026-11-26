import { isApiError } from '../api/problem'
import { i18n } from './index'

/**
 * Mirrors backend ErrorCode.java (checked by i18n.spec.ts). Never rename a code; add new ones here
 * and in both locale files.
 */
export const ERROR_CODES = [
  'VALIDATION_FAILED',
  'UNAUTHENTICATED',
  'INVALID_CREDENTIALS',
  'SESSION_STALE',
  'REFRESH_TOKEN_INVALID',
  'REFRESH_RACE',
  'SUBSCRIPTION_INACTIVE',
  'FORBIDDEN',
  'NOT_FOUND',
  'EMAIL_ALREADY_REGISTERED',
  'ACCESS_CODE_INVALID',
  'INVITE_INVALID',
  'INVITE_EXPIRED',
  'INVITE_SELF',
  'INVITE_PENDING_EXISTS',
  'ACCOUNT_FULL',
  'ALREADY_IN_COUPLE',
  'ACCOUNT_HAS_DATA',
  'NOT_IN_COUPLE',
  'EMAIL_NOT_VERIFIED',
  'TOKEN_INVALID',
  'BANK_ACCOUNT_ARCHIVED',
  'BANK_ACCOUNT_IN_USE',
  'CATEGORY_ARCHIVED',
  'CATEGORY_KIND_MISMATCH',
  'CATEGORY_NAME_TAKEN',
  'CATEGORY_IN_USE',
  'TRANSFER_INVALID',
  'RECURRING_DATE_TAKEN',
  'BUDGET_EXISTS',
  'BUDGET_CATEGORY_INVALID',
  'GOAL_NAME_TAKEN',
  'GOAL_PERCENT_CAP',
  'GOAL_COMPLETED',
  'GOAL_MONTH_TAKEN',
  'GOAL_MONTH_HAS_CONTRIBUTIONS',
  'GOAL_MOVEMENT_IMPORTED',
  'GOAL_MONTH_INVALID',
  'MONTH_NOT_CLOSED',
  'GOAL_AMOUNT_INVALID',
  'GOAL_NO_SALARY',
  'GOAL_SALARY_CATEGORY_INVALID',
  'IN_USE_BY_ACTIVE_RULE',
  'IMPORT_FORMAT_UNSUPPORTED',
  'IMPORT_ROW_INVALID',
  'IMPORT_TOO_MANY_ROWS',
  'IMPORT_ALREADY_UNDONE',
  'GOOGLE_TOKEN_INVALID',
  'GOOGLE_EMAIL_UNVERIFIED',
  'GOOGLE_SIGNUP_TICKET_INVALID',
  'GOOGLE_ACCOUNT_CONFLICT',
  'GOOGLE_LOGIN_DISABLED',
  'INTERNAL_ERROR',
] as const

export type ErrorCode = (typeof ERROR_CODES)[number]

/** Codes produced by the client itself, never by the API. */
export const CLIENT_ERROR_CODES = ['NETWORK_ERROR', 'UNKNOWN'] as const
export type ClientErrorCode = (typeof CLIENT_ERROR_CODES)[number]

/** Translated, user-facing message for any thrown value; never exposes the raw `detail`. */
export function errorMessage(e: unknown): string {
  const t = i18n.global.t
  if (isApiError(e) && i18n.global.te(`errors.${e.code}`)) return t(`errors.${e.code}`)
  return t('errors.UNKNOWN')
}
