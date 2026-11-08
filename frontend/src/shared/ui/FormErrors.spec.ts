import { describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/shared/api/problem'
import { applyFieldErrors } from './FormErrors'

describe('applyFieldErrors', () => {
  it('maps each field error to its validation message', () => {
    const set = vi.fn()
    const e = new ApiError({
      status: 400,
      code: 'VALIDATION_FAILED',
      fieldErrors: [
        { field: 'password', code: 'Size' },
        { field: 'email', code: 'Email' },
      ],
    })
    expect(applyFieldErrors(e, set)).toBe(true)
    expect(set).toHaveBeenCalledWith('password', expect.stringContaining('tamanho'))
    expect(set).toHaveBeenCalledWith('email', expect.stringContaining('e-mail'))
  })

  it('falls back to a generic message for unknown codes', () => {
    const set = vi.fn()
    const e = new ApiError({
      status: 400,
      code: 'VALIDATION_FAILED',
      fieldErrors: [{ field: 'x', code: 'Weird' }],
    })
    expect(applyFieldErrors(e, set)).toBe(true)
    expect(set.mock.calls[0]?.[1]).not.toContain('validation.')
  })

  it('returns false without field errors or for other errors', () => {
    const set = vi.fn()
    expect(applyFieldErrors(new ApiError({ status: 500, code: 'INTERNAL_ERROR' }), set)).toBe(false)
    expect(applyFieldErrors(new Error('x'), set)).toBe(false)
    expect(set).not.toHaveBeenCalled()
  })
})
