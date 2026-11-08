import type { components } from './schema'

type Schemas = components['schemas']

export type AuthResponse = Schemas['AuthResponse']
export type MeResponse = Schemas['MeResponse']
export type AccountResponse = Schemas['AccountResponse']
export type InvitationSummary = Schemas['InvitationSummary']
export type InvitationPreviewResponse = Schemas['InvitationPreviewResponse']
export type SubscriptionResponse = Schemas['SubscriptionResponse']
export type BankAccountResponse = Schemas['BankAccountResponse']
export type CategoryResponse = Schemas['CategoryResponse']
export type TransactionResponse = Schemas['TransactionResponse']
export type PageResponseTransactionResponse = Schemas['PageResponseTransactionResponse']
export type RecurringRuleResponse = Schemas['RecurringRuleResponse']
export type BudgetResponse = Schemas['BudgetResponse']
export type DashboardResponse = Schemas['DashboardResponse']
export type WaitlistEntryResponse = Schemas['WaitlistEntryResponse']
export type AccessCodeResponse = Schemas['AccessCodeResponse']
export type CreatedAccessCodeResponse = Schemas['CreatedAccessCodeResponse']

/** Mirrors `ErrorCode.java`; the API sends it as `code` in every ProblemDetail. */
export type ErrorCode =
  | 'VALIDATION_FAILED'
  | 'UNAUTHENTICATED'
  | 'INVALID_CREDENTIALS'
  | 'SESSION_STALE'
  | 'REFRESH_TOKEN_INVALID'
  | 'REFRESH_RACE'
  | 'SUBSCRIPTION_INACTIVE'
  | 'FORBIDDEN'
  | 'NOT_FOUND'
  | 'EMAIL_ALREADY_REGISTERED'
  | 'ACCESS_CODE_INVALID'
  | 'INVITE_INVALID'
  | 'INVITE_EXPIRED'
  | 'INVITE_SELF'
  | 'INVITE_PENDING_EXISTS'
  | 'ACCOUNT_FULL'
  | 'ALREADY_IN_COUPLE'
  | 'ACCOUNT_HAS_DATA'
  | 'NOT_IN_COUPLE'
  | 'EMAIL_NOT_VERIFIED'
  | 'TOKEN_INVALID'
  | 'BANK_ACCOUNT_ARCHIVED'
  | 'BANK_ACCOUNT_IN_USE'
  | 'CATEGORY_ARCHIVED'
  | 'CATEGORY_KIND_MISMATCH'
  | 'CATEGORY_NAME_TAKEN'
  | 'CATEGORY_IN_USE'
  | 'TRANSFER_INVALID'
  | 'RECURRING_DATE_TAKEN'
  | 'BUDGET_EXISTS'
  | 'BUDGET_CATEGORY_INVALID'
  | 'IN_USE_BY_ACTIVE_RULE'
  | 'INTERNAL_ERROR'
