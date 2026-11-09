import type { APIRequestContext } from '@playwright/test'

type OutboxMessage = { to: string; subject: string; html: string; links: string[] }

const BASE_URL = 'http://localhost:5173'
const TIMEOUT_MS = 10_000
const INTERVAL_MS = 250

/**
 * Polls the e2e outbox (newest message first) for up to 10 s and returns the first link
 * whose path starts with `pathPrefix`, with its origin rewritten to the app's base URL.
 */
export async function latestLink(
  request: APIRequestContext,
  email: string,
  pathPrefix: string,
): Promise<string> {
  const deadline = Date.now() + TIMEOUT_MS
  let lastFailure: string
  for (;;) {
    const res = await request.get('/api/v1/__e2e/outbox', { params: { to: email } })
    if (res.ok()) {
      const messages = (await res.json()) as OutboxMessage[]
      const found = messages
        .flatMap((m) => m.links)
        .find((link) => new URL(link).pathname.startsWith(pathPrefix))
      if (found) {
        const url = new URL(found)
        return new URL(url.pathname + url.search + url.hash, BASE_URL).toString()
      }
      lastFailure = `outbox had ${messages.length} message(s), none with a ${pathPrefix} link`
    } else {
      // 404 usually means the backend runs without the `e2e` profile.
      lastFailure = `outbox answered HTTP ${res.status()} (is the backend on profiles dev,e2e?)`
    }
    if (Date.now() >= deadline) {
      throw new Error(`No ${pathPrefix} link e-mailed to ${email} within 10 s: ${lastFailure}`)
    }
    await new Promise((resolve) => setTimeout(resolve, INTERVAL_MS))
  }
}
