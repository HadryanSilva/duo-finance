import { describe, expectTypeOf, it } from 'vitest'
import type {
  AuthResponse,
  BudgetResponse,
  ErrorCode,
  PageResponseTransactionResponse,
  TransactionResponse,
} from './types'

describe('generated API types', () => {
  it('TransactionResponse has amountCents number and date string', () => {
    expectTypeOf<TransactionResponse['amountCents']>().toEqualTypeOf<number>()
    expectTypeOf<TransactionResponse['date']>().toEqualTypeOf<string>()
  })

  it('transfers may have no category and deleted authors have no createdBy', () => {
    expectTypeOf<undefined>().toExtend<TransactionResponse['category']>()
    expectTypeOf<undefined>().toExtend<TransactionResponse['createdBy']>()
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
