import { afterEach, describe, expect, it } from 'vitest'
import { effectScope } from 'vue'
import { useIsDesktop } from './breakpoints'

function measure(): boolean {
  const scope = effectScope()
  const value = scope.run(() => useIsDesktop().value)
  scope.stop()
  return value ?? false
}

afterEach(() => {
  window.innerWidth = 1024 // jsdom's default
})

describe('useIsDesktop', () => {
  it('useIsDesktop_trueAt1024', () => {
    window.innerWidth = 1024
    expect(measure()).toBe(true)
  })

  it('useIsDesktop_falseAt375', () => {
    window.innerWidth = 375
    expect(measure()).toBe(false)
  })
})
