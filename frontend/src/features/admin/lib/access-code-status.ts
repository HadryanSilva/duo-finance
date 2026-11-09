import type { AccessCodeResponse } from '@/shared/api/types'

export type AccessCodeStatus = 'ACTIVE' | 'REVOKED' | 'EXPIRED' | 'EXHAUSTED'

/** Precedence: revoked > expired > exhausted > active. `expiresAt` is an Instant, not a business date. */
export function accessCodeStatus(code: AccessCodeResponse, now: Date): AccessCodeStatus {
  if (code.revokedAt) return 'REVOKED'
  if (code.expiresAt && new Date(code.expiresAt).getTime() <= now.getTime()) return 'EXPIRED'
  if (code.usedCount >= code.maxUses) return 'EXHAUSTED'
  return 'ACTIVE'
}
