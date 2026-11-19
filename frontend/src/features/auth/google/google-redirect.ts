import { safeRedirect } from '@/shared/session/safe-redirect'
import { leaveApp } from './leave-app'

const AUTH_ENDPOINT = 'https://accounts.google.com/o/oauth2/v2/auth'
export const CALLBACK_PATH = '/auth/google/callback'
const STORAGE_KEY = 'duo.google-sign-in'

/** What the page that started the sign-in hands to the callback page, across the redirect. */
export interface GoogleSignInStart {
  clientId: string
  /** Where to go once signed in. Must be an in-app path; anything else falls back to /dashboard. */
  returnTo: string
  /** Where to go back to if the user cancels on Google's page. */
  cancelTo: string
  invitationToken?: string
}

interface Pending {
  state: string
  nonce: string
  returnTo: string
  cancelTo: string
  invitationToken?: string
}

export type GoogleCallback =
  | { kind: 'ok'; credential: string; returnTo: string; invitationToken?: string }
  | { kind: 'cancelled'; cancelTo: string }
  | { kind: 'invalid' }

const randomToken = () => {
  const bytes = crypto.getRandomValues(new Uint8Array(32))
  return Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join('')
}

/** The Google sign-in URL (OpenID Connect implicit flow: Google answers with an ID token in the URL fragment). */
export function googleAuthUrl(clientId: string, state: string, nonce: string): string {
  const params = new URLSearchParams({
    client_id: clientId,
    redirect_uri: `${window.location.origin}${CALLBACK_PATH}`,
    response_type: 'id_token',
    scope: 'openid email profile',
    state,
    nonce,
    prompt: 'select_account',
  })
  return `${AUTH_ENDPOINT}?${params.toString()}`
}

/**
 * Sends the browser to Google's sign-in page. Nothing from Google loads on our pages: the user only reaches Google
 * after clicking. `state` (login CSRF) and `nonce` (token replay) are kept in sessionStorage for the callback.
 */
export function startGoogleSignIn(start: GoogleSignInStart): void {
  const pending: Pending = {
    state: randomToken(),
    nonce: randomToken(),
    returnTo: safeRedirect(start.returnTo),
    cancelTo: safeRedirect(start.cancelTo),
    ...(start.invitationToken ? { invitationToken: start.invitationToken } : {}),
  }
  sessionStorage.setItem(STORAGE_KEY, JSON.stringify(pending))
  leaveApp(googleAuthUrl(start.clientId, pending.state, pending.nonce))
}

function takePending(): Pending | null {
  const raw = sessionStorage.getItem(STORAGE_KEY)
  sessionStorage.removeItem(STORAGE_KEY)
  if (!raw) return null
  try {
    const p = JSON.parse(raw) as Partial<Pending>
    return typeof p.state === 'string' && typeof p.nonce === 'string'
      ? {
          state: p.state,
          nonce: p.nonce,
          returnTo: safeRedirect(p.returnTo),
          cancelTo: safeRedirect(p.cancelTo),
          ...(typeof p.invitationToken === 'string' ? { invitationToken: p.invitationToken } : {}),
        }
      : null
  } catch {
    return null
  }
}

/** The `nonce` claim of an ID token, read without verifying it: the server verifies the signature. */
function nonceOf(idToken: string): string | null {
  const payload = idToken.split('.')[1]
  if (!payload) return null
  try {
    const json = atob(payload.replace(/-/g, '+').replace(/_/g, '/'))
    const claims = JSON.parse(json) as { nonce?: unknown }
    return typeof claims.nonce === 'string' ? claims.nonce : null
  } catch {
    return null
  }
}

/**
 * Reads Google's answer from the callback URL fragment. The pending sign-in is consumed whatever the outcome, so a
 * reloaded or replayed callback URL is rejected. A token whose `state` or `nonce` does not match what this tab sent
 * is rejected: it was not started here (login CSRF) or was replayed.
 */
export function readGoogleCallback(hash: string): GoogleCallback {
  const params = new URLSearchParams(hash.startsWith('#') ? hash.slice(1) : hash)
  const pending = takePending()
  if (!pending || params.get('state') !== pending.state) return { kind: 'invalid' }
  if (params.get('error')) return { kind: 'cancelled', cancelTo: pending.cancelTo }
  const credential = params.get('id_token')
  if (!credential || nonceOf(credential) !== pending.nonce) return { kind: 'invalid' }
  return {
    kind: 'ok',
    credential,
    returnTo: pending.returnTo,
    ...(pending.invitationToken ? { invitationToken: pending.invitationToken } : {}),
  }
}
