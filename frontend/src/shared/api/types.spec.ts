import { describe, expectTypeOf, it } from 'vitest'
import type {
  AccountResponse,
  AuthResponse,
  BudgetResponse,
  ErrorCode,
  PageResponseTransactionResponse,
  RecurringRuleResponse,
  TransactionResponse,
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

  it('ErrorCode is a literal union', () => {
    expectTypeOf<'SUBSCRIPTION_INACTIVE'>().toExtend<ErrorCode>()
    expectTypeOf<string>().not.toExtend<ErrorCode>()
  })
})
