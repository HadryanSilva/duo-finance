import type { ClientErrorCode, ErrorCode } from '../i18n/error-codes'

export interface FieldError {
  field: string
  code: string
}

export class ApiError extends Error {
  readonly status: number
  readonly code: ErrorCode | ClientErrorCode
  readonly fieldErrors: FieldError[]
  /** The 1-based statement line an IMPORT_ROW_INVALID points at, when the server sent one. */
  readonly line?: number

  constructor(init: {
    status: number
    code: ErrorCode | ClientErrorCode
    fieldErrors?: FieldError[]
    line?: number
    message?: string
  }) {
    super(init.message ?? init.code)
    this.name = 'ApiError'
    this.status = init.status
    this.code = init.code
    this.fieldErrors = init.fieldErrors ?? []
    if (init.line !== undefined) this.line = init.line
  }
}

export function isApiError(e: unknown): e is ApiError {
  return e instanceof ApiError
}
