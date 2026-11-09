import { useMutationObserver } from '@vueuse/core'
import { ref, type Ref } from 'vue'

export interface ChartColors {
  income: string
  expense: string
  /** Axis labels. */
  muted: string
  /** Axis lines and gridlines. */
  border: string
  /** The gap between doughnut slices; tooltip background. */
  surface: string
  /** Tooltip text. */
  text: string
}

const TOKENS: Record<keyof ChartColors, string> = {
  income: '--color-income',
  expense: '--color-expense',
  muted: '--color-text-muted',
  border: '--color-border',
  surface: '--color-surface',
  text: '--color-text',
}

/** Resolves the design tokens to concrete colors: canvas charts cannot use CSS variables. */
function readColors(): ChartColors {
  const style = getComputedStyle(document.documentElement)
  const read = (token: string) => style.getPropertyValue(token).trim()
  return {
    income: read(TOKENS.income),
    expense: read(TOKENS.expense),
    muted: read(TOKENS.muted),
    border: read(TOKENS.border),
    surface: read(TOKENS.surface),
    text: read(TOKENS.text),
  }
}

/** Chart colors from the tokens, re-read whenever the theme (the `dark` class on <html>) changes. */
export function useChartColors(): Ref<ChartColors> {
  const colors = ref(readColors())
  useMutationObserver(
    document.documentElement,
    () => {
      colors.value = readColors()
    },
    { attributes: true, attributeFilter: ['class'] },
  )
  return colors
}
