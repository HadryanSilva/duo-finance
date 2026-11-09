import type { ClientErrorCode, ErrorCode } from '../i18n/error-codes'

export interface FieldError {
  field: string
  code: string
}

export class ApiError extends Error {
  readonly status: number
  readonly code: ErrorCode | ClientErrorCode
  readonly fieldErrors: FieldError[]

  constructor(init: {
    status: number
    code: ErrorCode | ClientErrorCode
    fieldErrors?: FieldError[]
    message?: string
  }) {
    super(init.message ?? init.code)
    this.name = 'ApiError'
    this.status = init.status
    this.code = init.code
    this.fieldErrors = init.fieldErrors ?? []
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError
}
