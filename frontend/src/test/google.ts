const base64url = (s: string) => btoa(s).replace(/=+$/, '').replace(/\+/g, '-').replace(/\//g, '_')

/** A fake ID token: only the payload matters to the client, which never verifies the signature. */
export const fakeIdToken = (claims: Record<string, unknown>) =>
  `${base64url('{"alg":"RS256"}')}.${base64url(JSON.stringify(claims))}.sig`

/** The callback URL fragment Google would send back for the sign-in started with `authUrl`. */
export function googleAnswer(authUrl: string, over: Record<string, string> = {}): string {
  const url = new URL(authUrl)
  const params = new URLSearchParams({
    id_token: fakeIdToken({ nonce: url.searchParams.get('nonce') }),
    state: url.searchParams.get('state') ?? '',
    ...over,
  })
  return `#${params.toString()}`
}
