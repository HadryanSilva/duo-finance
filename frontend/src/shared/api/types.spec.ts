import { describe, expectTypeOf, it } from 'vitest'
import type { Http } from './http'
import type {
  AcceptInvitationRequest,
  AccountResponse,
  AuthResponse,
  BudgetResponse,
  CreateInvitationRequest,
  ForgotPasswordRequest,
  InvitationPreviewResponse,
  ErrorCode,
  LoginRequest,
  PageResponseTransactionResponse,
  RecurringRuleResponse,
  RegistrationMode,
  RegistrationModeResponse,
  ResetPasswordRequest,
  TokenRequest,
  TransactionResponse,
  UpdateAccountRequest,
} from './types'

type AuthorRef = NonNullable<TransactionResponse['createdBy']>

describe('generated API types', () => {
  it('TransactionResponse has amountCents number and date string', () => {
    expectTypeOf<TransactionResponse['amountCents']>().toEqualTypeOf<number>()
    expectTypeOf<TransactionResponse['date']>().toEqualTypeOf<string>()
  })

  it('nullable fields are required and typed T | null, not undefined', () => {
    expectTypeOf<TransactionResponse['createdBy']>().toEqualTypeOf<AuthorRef | null>()
    expectTypeOf<TransactionResponse['category']>().toEqualTypeOf<NonNullable<
      TransactionResponse['category']
    > | null>()
    expectTypeOf<TransactionResponse['bankAccount']>().toEqualTypeOf<NonNullable<
      TransactionResponse['bankAccount']
    > | null>()
    expectTypeOf<TransactionResponse['description']>().toEqualTypeOf<string | null>()
    expectTypeOf<RecurringRuleResponse['endDate']>().toEqualTypeOf<string | null>()
    expectTypeOf<RecurringRuleResponse['bankAccount']>().toEqualTypeOf<NonNullable<
      RecurringRuleResponse['bankAccount']
    > | null>()
    expectTypeOf<AccountResponse['pendingInvitation']>().toEqualTypeOf<NonNullable<
      AccountResponse['pendingInvitation']
    > | null>()
    expectTypeOf<undefined>().not.toExtend<TransactionResponse['createdBy']>()
  })

  it('invitation preview inviterName is string | null', () => {
    expectTypeOf<InvitationPreviewResponse['inviterName']>().toEqualTypeOf<string | null>()
  })

  it('non-null fields stay non-null', () => {
    expectTypeOf<null>().not.toExtend<TransactionResponse['id']>()
    expectTypeOf<null>().not.toExtend<TransactionResponse['date']>()
    expectTypeOf<null>().not.toExtend<RecurringRuleResponse['nextRunDate']>()
    expectTypeOf<null>().not.toExtend<AccountResponse['members']>()
  })

  it('pages carry typed content', () => {
    expectTypeOf<PageResponseTransactionResponse['content']>().toEqualTypeOf<
      TransactionResponse[]
    >()
  })

  it('auth and budget payloads expose their required fields', () => {
    expectTypeOf<AuthResponse['accessToken']>().toEqualTypeOf<string>()
    expectTypeOf<BudgetResponse['monthlyLimitCents']>().toEqualTypeOf<number>()
  })

  it('request bodies have aliases over the generated schema', () => {
    expectTypeOf<LoginRequest>().toEqualTypeOf<{ email: string; password: string }>()
    expectTypeOf<TokenRequest>().toEqualTypeOf<{ token: string }>()
    expectTypeOf<ForgotPasswordRequest>().toEqualTypeOf<{ email: string }>()
    expectTypeOf<ResetPasswordRequest>().toEqualTypeOf<{ token: string; newPassword: string }>()
    expectTypeOf<AcceptInvitationRequest>().toEqualTypeOf<{ confirmDiscard?: boolean }>()
    expectTypeOf<UpdateAccountRequest>().toEqualTypeOf<{ name: string }>()
    expectTypeOf<CreateInvitationRequest>().toEqualTypeOf<{ email: string }>()
    expectTypeOf<RegistrationModeResponse['mode']>().toEqualTypeOf<RegistrationMode>()
  })

  it('http write methods check the body against the type the caller names', () => {
    // Compiled by `vue-tsc -b` (npm run build); never called.
    const check = (http: Http) => {
      void http.post<void, TokenRequest>('/auth/verify-email', { token: 't' })
      // @ts-expect-error a body missing a required field is rejected
      void http.post<void, ResetPasswordRequest>('/auth/reset-password', { token: 't' })
      // @ts-expect-error a body with the wrong field type is rejected
      void http.patch<AccountResponse, UpdateAccountRequest>('/account', { name: 1 })
      // @ts-expect-error put names its body type too
      void http.put<void, CreateInvitationRequest>('/x', { mail: 'a@b.c' })
    }
    expectTypeOf(check).toBeFunction()
  })

  it('ErrorCode is a literal union', () => {
    expectTypeOf<'SUBSCRIPTION_INACTIVE'>().toExtend<ErrorCode>()
    expectTypeOf<string>().not.toExtend<ErrorCode>()
  })
})
