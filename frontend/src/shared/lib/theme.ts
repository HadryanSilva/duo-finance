export type Theme = 'light' | 'dark' | 'system'

const KEY = 'df.theme'
const THEMES: readonly Theme[] = ['light', 'dark', 'system']
const DARK_QUERY = '(prefers-color-scheme: dark)'

let systemMedia: MediaQueryList | null = null

function onSystemChange(e: { matches: boolean }): void {
  document.documentElement.classList.toggle('dark', e.matches)
}

function systemPrefersDark(): MediaQueryList | null {
  return typeof window.matchMedia === 'function' ? window.matchMedia(DARK_QUERY) : null
}

function apply(theme: Theme): void {
  systemMedia?.removeEventListener('change', onSystemChange)
  systemMedia = null
  let dark = theme === 'dark'
  if (theme === 'system') {
    systemMedia = systemPrefersDark()
    systemMedia?.addEventListener('change', onSystemChange)
    dark = systemMedia?.matches ?? false
  }
  document.documentElement.classList.toggle('dark', dark)
}

export function getTheme(): Theme {
  try {
    const stored = localStorage.getItem(KEY)
    return THEMES.find((t) => t === stored) ?? 'system'
  } catch {
    return 'system'
  }
}

export function setTheme(theme: Theme): void {
  try {
    localStorage.setItem(KEY, theme)
  } catch {
    // storage unavailable (private mode, blocked): the theme still applies for this page
  }
  apply(theme)
}

/** Applies the stored theme; call before mounting to avoid a flash of the wrong theme. */
export function initTheme(): void {
  apply(getTheme())
}
