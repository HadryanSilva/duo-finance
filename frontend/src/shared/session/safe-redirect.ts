const FALLBACK = '/dashboard'
const BACKSLASH = String.fromCharCode(92)

/** True for control characters and backslash: browsers normalise them, so "/\t/host" can become "//host". */
function hasUnsafeChar(s: string): boolean {
  for (const ch of s) {
    const code = ch.codePointAt(0) ?? 0
    if (code <= 0x1f || code === 0x7f || ch === BACKSLASH) return true
  }
  return false
}

/**
 * Validates a post-login `?redirect=` value. Only in-app paths pass: a single leading "/",
 * never "//" (protocol-relative), a backslash or a scheme. Anything else becomes /dashboard.
 */
export function safeRedirect(raw: unknown): string {
  if (typeof raw !== 'string') return FALLBACK
  if (!raw.startsWith('/') || raw.startsWith('//') || hasUnsafeChar(raw)) return FALLBACK
  return raw
}
