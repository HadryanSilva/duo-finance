/** Relative luminance (WCAG) of a #rrggbb color; 0 for anything else. */
export function luminance(hex: string): number {
  const m = /^#([0-9a-f]{6})$/i.exec(hex)
  if (!m?.[1]) return 0
  const n = parseInt(m[1], 16)
  const [r, g, b] = [(n >> 16) & 255, (n >> 8) & 255, n & 255].map((c) => {
    const v = c / 255
    return v <= 0.03928 ? v / 12.92 : ((v + 0.055) / 1.055) ** 2.4
  }) as [number, number, number]
  return 0.2126 * r + 0.7152 * g + 0.0722 * b
}

/** Token class for the icon drawn over a swatch of this color: dark ink or white, whichever contrasts more. */
export function swatchInkClass(hex: string): 'text-swatch-ink' | 'text-swatch-paper' {
  const l = luminance(hex)
  // whichever of the two inks has the higher WCAG contrast (the ink token has luminance ~0.009)
  return (l + 0.05) / 0.059 > 1.05 / (l + 0.05) ? 'text-swatch-ink' : 'text-swatch-paper'
}
