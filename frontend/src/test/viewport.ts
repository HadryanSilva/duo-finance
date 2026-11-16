/**
 * jsdom has no matchMedia. This stub answers `(min-width: Nrem)` from window.innerWidth (jsdom's
 * default 1024, so specs run as desktop unless they set a phone width); any other query does not
 * match. setViewportWidth() also notifies live queries, like a rotation or window resize.
 */
class StubMediaQueryList extends EventTarget implements MediaQueryList {
  onchange: ((this: MediaQueryList, ev: MediaQueryListEvent) => unknown) | null = null
  matches: boolean
  readonly media: string

  constructor(media: string) {
    super()
    this.media = media
    this.matches = evaluate(media)
  }

  addListener(listener: ((ev: MediaQueryListEvent) => unknown) | null): void {
    if (listener) this.addEventListener('change', listener as EventListener)
  }

  removeListener(listener: ((ev: MediaQueryListEvent) => unknown) | null): void {
    if (listener) this.removeEventListener('change', listener as EventListener)
  }
}

const live = new Set<StubMediaQueryList>()

function evaluate(media: string): boolean {
  const rem = /\(min-width:\s*([\d.]+)rem\)/.exec(media)?.[1]
  return rem !== undefined && window.innerWidth >= Number(rem) * 16
}

export function installMatchMediaStub(): void {
  Object.defineProperty(window, 'matchMedia', {
    configurable: true,
    writable: true,
    value: (media: string) => {
      const mql = new StubMediaQueryList(media)
      live.add(mql)
      return mql
    },
  })
}

/** Changes the viewport width and fires `change` on every query whose answer flipped. */
export function setViewportWidth(width: number): void {
  window.innerWidth = width
  for (const mql of live) {
    const matches = evaluate(mql.media)
    if (matches === mql.matches) continue
    mql.matches = matches
    const event = Object.assign(new Event('change'), { matches, media: mql.media })
    mql.dispatchEvent(event)
  }
}

/** Forgets the queries of the previous test (called after each test). */
export function resetViewport(): void {
  live.clear()
  window.innerWidth = 1024
}
