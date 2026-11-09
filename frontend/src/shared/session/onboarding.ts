const key = (userId: string) => `df.onboarded.${userId}`

/** Whether this browser saw the user finish or skip onboarding. Unreadable storage counts as not. */
export function isOnboarded(userId: string): boolean {
  try {
    return localStorage.getItem(key(userId)) === '1'
  } catch {
    return false
  }
}

export function markOnboarded(userId: string): void {
  try {
    localStorage.setItem(key(userId), '1')
  } catch {
    // storage unavailable: onboarding is offered again next session
  }
}

export function clearOnboarded(userId: string): void {
  try {
    localStorage.removeItem(key(userId))
  } catch {
    // storage unavailable
  }
}
