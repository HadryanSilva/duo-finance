/** Full-page navigation away from the app. Its own module so tests can replace it (jsdom cannot navigate). */
export function leaveApp(url: string): void {
  window.location.assign(url)
}
