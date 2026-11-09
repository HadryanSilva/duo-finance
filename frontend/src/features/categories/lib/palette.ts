export const CATEGORY_COLOR_COUNT = 12

/**
 * Hex values of the --color-cat-1..12 tokens, read from the document at call time. The hex list
 * lives only in styles.css, so the API receives exactly what the swatches show.
 */
export function readCategoryPalette(): string[] {
  const style = getComputedStyle(document.documentElement)
  return Array.from({ length: CATEGORY_COLOR_COUNT }, (_, i) =>
    style
      .getPropertyValue(`--color-cat-${i + 1}`)
      .trim()
      .toLowerCase(),
  )
}
