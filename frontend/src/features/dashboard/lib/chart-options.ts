import type { EChartsCoreOption } from 'echarts/core'
import type { DashboardResponse } from '@/shared/api/types'
import { formatMoney, type Locale } from '@/shared/lib/money'
import type { ChartColors } from './chart-colors'

type Tooltip = { name: string; value: number }

/** The tooltip is drawn by ECharts, so it takes the theme colors explicitly. */
const tooltip = (colors: ChartColors, formatter: (p: Tooltip) => string) => ({
  trigger: 'item',
  formatter,
  backgroundColor: colors.surface,
  borderColor: colors.border,
  textStyle: { color: colors.text },
})

/** Income x expense bars; values stay in cents and are formatted for display only. */
export function incomeExpenseOption(
  data: Pick<DashboardResponse, 'incomeCents' | 'expenseCents'>,
  colors: ChartColors,
  labels: { income: string; expense: string },
  locale: Locale,
): EChartsCoreOption {
  const money = (cents: number) => formatMoney(cents, locale)
  return {
    grid: { left: 8, right: 8, top: 16, bottom: 8, containLabel: true },
    tooltip: tooltip(colors, (p) => `${p.name}: ${money(p.value)}`),
    xAxis: {
      type: 'category',
      data: [labels.income, labels.expense],
      axisLabel: { color: colors.muted },
      axisLine: { lineStyle: { color: colors.border } },
      axisTick: { show: false },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: colors.muted, formatter: (v: number) => money(v) },
      splitLine: { lineStyle: { color: colors.border } },
    },
    series: [
      {
        type: 'bar',
        barMaxWidth: 64,
        data: [
          { value: data.incomeCents, itemStyle: { color: colors.income } },
          { value: data.expenseCents, itemStyle: { color: colors.expense } },
        ],
      },
    ],
  }
}

/** Doughnut of spending; each slice takes the color of its category. */
export function spendingOption(
  spending: DashboardResponse['spendingByCategory'],
  colors: ChartColors,
  locale: Locale,
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
        data: spending.map((c) => ({
          name: c.name,
          value: c.totalCents,
          // the category's own color comes from the API, not from the tokens
          itemStyle: { color: c.color },
        })),
      },
    ],
  }
}
