import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { effectScope } from 'vue'
import { useToday } from './use-today'

describe('useToday', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 10, 21, 23, 59, 30))
  })
  afterEach(() => vi.useRealTimers())

  it('startsAtTheLocalDate', () => {
    const scope = effectScope()
    const today = scope.run(() => useToday())!
    expect(today.value).toBe('2026-11-21')
    scope.stop()
  })

  it('movesPastMidnightWithinAMinute', () => {
    const scope = effectScope()
    const today = scope.run(() => useToday())!
    vi.advanceTimersByTime(60_000)
    expect(today.value).toBe('2026-11-22')
    scope.stop()
  })

  it('catchesUpWhenTheTabBecomesVisible', () => {
    const scope = effectScope()
    const today = scope.run(() => useToday())!
    // a sleeping tab's timers may not fire; the clock jumps and the tab comes back
    vi.setSystemTime(new Date(2026, 10, 23, 8, 0, 0))
    document.dispatchEvent(new Event('visibilitychange'))
    expect(today.value).toBe('2026-11-23')
    scope.stop()
  })

  it('stopsWithItsScope', () => {
    const scope = effectScope()
    const today = scope.run(() => useToday())!
    scope.stop()
    vi.advanceTimersByTime(60_000)
    expect(today.value).toBe('2026-11-21')
  })
})
