import { afterEach, describe, expect, it, vi } from 'vitest'
import { getTheme, initTheme, setTheme } from './theme'

const html = () => document.documentElement

function mockMatchMedia(dark: boolean) {
  const listeners = new Set<(e: { matches: boolean }) => void>()
  const mql = {
    matches: dark,
    addEventListener: (_: string, l: (e: { matches: boolean }) => void) => listeners.add(l),
    removeEventListener: (_: string, l: (e: { matches: boolean }) => void) => listeners.delete(l),
  }
  vi.stubGlobal(
    'matchMedia',
    vi.fn(() => mql),
  )
  return {
    change(matches: boolean) {
      mql.matches = matches
      for (const l of listeners) l({ matches })
    },
    listeners,
  }
}

afterEach(() => {
  setTheme('light')
  localStorage.clear()
  vi.unstubAllGlobals()
  vi.restoreAllMocks()
})

describe('theme', () => {
  it('persists theme and toggles dark class', () => {
    expect(getTheme()).toBe('system')
    setTheme('dark')
    expect(localStorage.getItem('df.theme')).toBe('dark')
    expect(getTheme()).toBe('dark')
    expect(html().classList.contains('dark')).toBe(true)
    setTheme('light')
    expect(getTheme()).toBe('light')
    expect(html().classList.contains('dark')).toBe(false)
  })

  it('system follows prefers-color-scheme and its changes', () => {
    const media = mockMatchMedia(true)
    setTheme('system')
    expect(html().classList.contains('dark')).toBe(true)
    media.change(false)
    expect(html().classList.contains('dark')).toBe(false)
    setTheme('light')
    expect(media.listeners.size).toBe(0)
  })

  it('initTheme applies the stored theme', () => {
    localStorage.setItem('df.theme', 'dark')
    initTheme()
    expect(html().classList.contains('dark')).toBe(true)
  })

  it('ignores an invalid stored value', () => {
    localStorage.setItem('df.theme', 'neon')
    expect(getTheme()).toBe('system')
  })

  it('survives localStorage throwing', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('denied')
    })
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('denied')
    })
    expect(getTheme()).toBe('system')
    expect(() => setTheme('dark')).not.toThrow()
    expect(html().classList.contains('dark')).toBe(true)
  })
})
