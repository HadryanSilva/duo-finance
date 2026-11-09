import { readFileSync } from 'node:fs'
import { join } from 'node:path'
import { describe, expect, it } from 'vitest'
import { swatchInkClass } from '@/features/categories/lib/contrast'

const css = readFileSync(join(__dirname, 'styles.css'), 'utf8')
const palette = Array.from({ length: 12 }, (_, i) => {
  const m = new RegExp(String.raw`--color-cat-${i + 1}:[ ]*(#[0-9a-fA-F]{6});`).exec(css)
  return m?.[1]?.toLowerCase() ?? ''
})

function hsl(hex: string): { h: number; s: number } {
  const n = parseInt(hex.slice(1), 16)
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((c) => c / 255) as [
    number,
    number,
    number,
  ]
  const max = Math.max(r, g, b)
  const min = Math.min(r, g, b)
  const d = max - min
  const l = (max + min) / 2
  const s = d === 0 ? 0 : d / (1 - Math.abs(2 * l - 1))
  let h = 0
  if (d !== 0) {
    if (max === r) h = ((g - b) / d) % 6
    else if (max === g) h = (b - r) / d + 2
    else h = (r - g) / d + 4
  }
  return { h: (h * 60 + 360) % 360, s: s * 100 }
}

describe('category palette', () => {
  it('has 12 distinct hex tokens', () => {
    expect(palette.every((c) => /^#[0-9a-f]{6}$/.test(c))).toBe(true)
    expect(new Set(palette).size).toBe(12)
  })

  it('offers no red, green or amber (reserved for money semantics)', () => {
    for (const hex of palette) {
      const { h, s } = hsl(hex)
      if (s < 25) continue
      const red = h >= 340 || h <= 15
      const green = h >= 90 && h <= 160
      const amber = h >= 35 && h <= 60
      expect(red || green || amber, `${hex} hue ${Math.round(h)}`).toBe(false)
    }
  })

  it('picks dark ink on light swatches and white on dark ones', () => {
    expect(swatchInkClass('#f9a8d4')).toBe('text-swatch-ink')
    expect(swatchInkClass('#1e3a8a')).toBe('text-swatch-paper')
    expect(swatchInkClass('#0ea5e9')).toBe('text-swatch-ink')
  })
})
