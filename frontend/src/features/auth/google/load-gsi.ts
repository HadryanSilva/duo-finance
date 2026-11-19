const GSI_SRC = 'https://accounts.google.com/gsi/client'

let pending: Promise<typeof google.accounts.id> | null = null

/** Loads the Google Identity Services script once and resolves with its `accounts.id` API. */
export function loadGsi(): Promise<typeof google.accounts.id> {
  pending ??= new Promise((resolve, reject) => {
    const script = document.createElement('script')
    script.src = GSI_SRC
    script.async = true
    script.onload = () => resolve(google.accounts.id)
    script.onerror = () => {
      pending = null // let a later attempt retry
      script.remove()
      reject(new Error('Failed to load Google Identity Services'))
    }
    document.head.appendChild(script)
  })
  return pending
}
