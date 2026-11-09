import { describe, expect, it } from 'vitest'
import { safeRedirect } from './safe-redirect'

describe('safeRedirect', () => {
  it.each([
    'https://evil.com',
    '//evil.com',
    '/\\evil.com',
    'javascript:alert(1)',
    '/\t/evil.com',
    'evil.com',
    '',
    undefined,
    null,
    ['/transactions'],
  ])('falls back to /dashboard for %j', (raw) => {
    expect(safeRedirect(raw)).toBe('/dashboard')
  })

  it('allows an in-app path with a query', () => {
    expect(safeRedirect('/transactions?x=1')).toBe('/transactions?x=1')
    expect(safeRedirect('/invite/abc')).toBe('/invite/abc')
  })
})
