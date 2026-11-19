import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { fakeIdToken, googleAnswer } from '@/test/google'
import { readGoogleCallback, startGoogleSignIn } from './google-redirect'

const leaveApp = vi.hoisted(() => vi.fn())
vi.mock('./leave-app', () => ({ leaveApp }))

const start = (over: Partial<Parameters<typeof startGoogleSignIn>[0]> = {}) => {
  startGoogleSignIn({ clientId: 'cid', returnTo: '/transactions', cancelTo: '/login', ...over })
  return leaveApp.mock.calls.at(-1)![0] as string
}

describe('google-redirect', () => {
  beforeEach(() => {
    leaveApp.mockClear()
    sessionStorage.clear()
  })
  afterEach(() => sessionStorage.clear())

  it('sends the browser to Google with an ID-token request back to the callback page', () => {
    const url = new URL(start())
    expect(url.origin + url.pathname).toBe('https://accounts.google.com/o/oauth2/v2/auth')
    expect(url.searchParams.get('client_id')).toBe('cid')
    expect(url.searchParams.get('response_type')).toBe('id_token')
    expect(url.searchParams.get('scope')).toBe('openid email profile')
    expect(url.searchParams.get('redirect_uri')).toBe(
      `${window.location.origin}/auth/google/callback`,
    )
    expect(url.searchParams.get('state')).toMatch(/^[0-9a-f]{64}$/)
    expect(url.searchParams.get('nonce')).toMatch(/^[0-9a-f]{64}$/)
    expect(url.searchParams.get('state')).not.toBe(url.searchParams.get('nonce'))
  })

  it('returns the credential, destination and invitation of the sign-in this tab started', () => {
    const url = start({ invitationToken: 'inv-1' })
    const result = readGoogleCallback(googleAnswer(url))
    expect(result).toMatchObject({
      kind: 'ok',
      returnTo: '/transactions',
      invitationToken: 'inv-1',
    })
  })

  it('rejects an answer this tab did not start (login CSRF)', () => {
    const url = start()
    expect(readGoogleCallback(googleAnswer(url, { state: 'other' }))).toEqual({ kind: 'invalid' })
  })

  it('rejects an answer when no sign-in was started', () => {
    const url = start()
    sessionStorage.clear()
    expect(readGoogleCallback(googleAnswer(url))).toEqual({ kind: 'invalid' })
  })

  it('rejects an ID token with another nonce (replayed token)', () => {
    const url = start()
    const answer = googleAnswer(url, { id_token: fakeIdToken({ nonce: 'old' }) })
    expect(readGoogleCallback(answer)).toEqual({ kind: 'invalid' })
  })

  it('rejects a malformed ID token', () => {
    const url = start()
    expect(readGoogleCallback(googleAnswer(url, { id_token: 'garbage' }))).toEqual({
      kind: 'invalid',
    })
  })

  it('accepts an answer only once', () => {
    const answer = googleAnswer(start())
    expect(readGoogleCallback(answer).kind).toBe('ok')
    expect(readGoogleCallback(answer)).toEqual({ kind: 'invalid' })
  })

  it('a cancelled sign-in goes back to where it started', () => {
    const url = new URL(start({ cancelTo: '/register?invite=x' }))
    const answer = `#error=access_denied&state=${url.searchParams.get('state')}`
    expect(readGoogleCallback(answer)).toEqual({
      kind: 'cancelled',
      cancelTo: '/register?invite=x',
    })
  })

  it('never redirects outside the app', () => {
    const url = start({ returnTo: '//evil.example', cancelTo: 'https://evil.example' })
    expect(readGoogleCallback(googleAnswer(url))).toMatchObject({ returnTo: '/dashboard' })
  })
})
