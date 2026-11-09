import { describe, expect, it } from 'vitest'
import { ApiError } from '@/shared/api/problem'
import { createQueryClient, shouldRetryQuery } from './query-client'

describe('query client', () => {
  it('retries queries once, never on 4xx ApiErrors', () => {
    const server = new ApiError({ status: 503, code: 'UNKNOWN' })
    expect(shouldRetryQuery(0, server)).toBe(true)
    expect(shouldRetryQuery(1, server)).toBe(false)
    expect(shouldRetryQuery(0, new ApiError({ status: 0, code: 'NETWORK_ERROR' }))).toBe(true)
    for (const status of [400, 401, 402, 404, 409]) {
      expect(shouldRetryQuery(0, new ApiError({ status, code: 'UNKNOWN' })), String(status)).toBe(
        false,
      )
    }
  })

  it('uses a 30 s stale time and never retries mutations', () => {
    const defaults = createQueryClient().getDefaultOptions()
    expect(defaults.queries?.staleTime).toBe(30_000)
    expect(defaults.mutations?.retry).toBe(false)
  })
})
