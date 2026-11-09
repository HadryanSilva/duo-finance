import { expect, type APIRequestContext } from '@playwright/test'

type OutboxMessage = { to: string; subject: string; html: string; links: string[] }

const BASE_URL = 'http://localhost:5173'

/**
 * Polls the e2e outbox (newest message first) for up to 10 s and returns the first link
 * whose path starts with `pathPrefix`, with its origin rewritten to the app's base URL.
 */
export async function latestLink(
  request: APIRequestContext,
  email: string,
  pathPrefix: string,
): Promise<string> {
  let found: string | undefined
  await expect
    .poll(
      async () => {
        const res = await request.get('/api/v1/__e2e/outbox', { params: { to: email } })
        if (!res.ok()) return undefined
        const messages = (await res.json()) as OutboxMessage[]
        found = messages
          .flatMap((m) => m.links)
          .find((link) => new URL(link).pathname.startsWith(pathPrefix))
        return found
      },
      { timeout: 10_000, message: `no ${pathPrefix} link e-mailed to ${email}` },
    )
    .toBeTruthy()
  const url = new URL(found as string)
  return new URL(url.pathname + url.search + url.hash, BASE_URL).toString()
}
