import { onTestFinished } from 'vitest'

/**
 * Makes closing dialogs and side panels animate out, as in a browser: jsdom has no animations, so
 * they would otherwise be removed at once. A closing one stays in the page (data-state="closed")
 * until the test ends, so what it shows while it slides out can be checked.
 */
export function holdClosingAnimation() {
  const style = document.createElement('style')
  style.textContent = '[role="dialog"][data-state="closed"] { animation-name: leave; }'
  document.head.appendChild(style)
  onTestFinished(() => style.remove())
}
