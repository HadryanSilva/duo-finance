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
export type RegisterRequest = Schemas['RegisterRequest']
export type RegistrationMode = Schemas['RegistrationModeResponse']['mode']

export type { ErrorCode } from '../i18n/error-codes'
export type JoinWaitlistRequest = Schemas['JoinWaitlistRequest']
/** The generated type models holderUserId as optional; the API also accepts an explicit null (joint account). */
export type BankAccountRequest = Omit<Schemas['BankAccountRequest'], 'holderUserId'> & {
  holderUserId?: string | null
}
