import { describe, expect, it } from 'vitest'
import type { AccessCodeResponse } from '@/shared/api/types'
import { accessCodeStatus } from './access-code-status'

const now = new Date('2026-11-09T12:00:00Z')
const base: AccessCodeResponse = {
  id: 'a',
  email: null,
  maxUses: 3,
  usedCount: 0,
  expiresAt: null,
  revokedAt: null,
  note: null,
  createdAt: '2026-11-01T00:00:00Z',
}
const past = '2026-11-08T12:00:00Z'
const future = '2026-11-10T12:00:00Z'

describe('accessCodeStatus', () => {
  it('accessCodes_statusDerivation', () => {
    expect(accessCodeStatus(base, now)).toBe('ACTIVE')
    expect(accessCodeStatus({ ...base, expiresAt: future }, now)).toBe('ACTIVE')
    expect(accessCodeStatus({ ...base, expiresAt: past }, now)).toBe('EXPIRED')
    expect(accessCodeStatus({ ...base, usedCount: 3 }, now)).toBe('EXHAUSTED')
    expect(accessCodeStatus({ ...base, usedCount: 4 }, now)).toBe('EXHAUSTED')
    expect(accessCodeStatus({ ...base, revokedAt: past }, now)).toBe('REVOKED')
  })

  it('accessCodes_statusPrecedence: revoked > expired > exhausted > active', () => {
    const all = { ...base, revokedAt: past, expiresAt: past, usedCount: 3 }
    expect(accessCodeStatus(all, now)).toBe('REVOKED')
    expect(accessCodeStatus({ ...all, revokedAt: null }, now)).toBe('EXPIRED')
    expect(accessCodeStatus({ ...all, revokedAt: null, expiresAt: future }, now)).toBe('EXHAUSTED')
    expect(accessCodeStatus({ ...base, expiresAt: past, usedCount: 3 }, now)).toBe('EXPIRED')
  })

  it('expiry is exclusive of the exact instant', () => {
    expect(accessCodeStatus({ ...base, expiresAt: now.toISOString() }, now)).toBe('EXPIRED')
  })
})
