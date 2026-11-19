import type { EChartsCoreOption } from 'echarts/core'
import { escapeHtml } from '@/shared/lib/html'
import { formatMoney, type Locale } from '@/shared/lib/money'
import type { ChartColors } from './chart-colors'
import type { SpendingSlice } from './figures'

type Tooltip = { name: string; value: number }

/**
 * The tooltip is drawn by ECharts, so it takes the theme colors explicitly.
 * ECharts writes the formatter's string with innerHTML and names are user input (category names,
 * shared between partners), so the whole text is HTML-escaped.
 */
const tooltip = (colors: ChartColors, text: (p: Tooltip) => string) => ({
  trigger: 'item',
  formatter: (p: Tooltip) => escapeHtml(text(p)),
  backgroundColor: colors.surface,
  borderColor: colors.border,
  textStyle: { color: colors.text },
})

/** Doughnut of spending; each slice takes the color of its category, the grouped rest is muted. */
export function spendingOption(
  slices: SpendingSlice[],
  colors: ChartColors,
  locale: Locale,
  othersName: (n: number) => string,
): EChartsCoreOption {
  return {
    tooltip: tooltip(colors, (p) => `${p.name}: ${formatMoney(p.value, locale)}`),
    series: [
      {
        type: 'pie',
        radius: ['55%', '80%'],
        label: { show: false },
        labelLine: { show: false },
        itemStyle: { borderColor: colors.surface, borderWidth: 2 },
        data: slices.map((s) => ({
          name: s.name ?? othersName(s.othersCount),
          value: s.totalCents,
          // a category's own color comes from the API, not from the tokens
          itemStyle: { color: s.color ?? colors.muted },
        })),
      },
    ],
  }
}
