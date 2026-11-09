import { init, use, type EChartsCoreOption } from 'echarts/core'
import { SVGRenderer } from 'echarts/renderers'
import { describe, expect, it, vi } from 'vitest'
import type { ChartColors } from './chart-colors'
import { incomeExpenseOption, spendingOption } from './chart-options'
import { ECHARTS_MODULES } from './echarts-modules'

// The global mock in test/setup.ts hides missing registrations: use the real ECharts here.
vi.unmock('echarts/core')
vi.unmock('echarts/charts')
vi.unmock('echarts/components')
vi.unmock('echarts/renderers')

const colors: ChartColors = {
  income: 'green',
  expense: 'red',
  muted: 'gray',
  border: 'silver',
  surface: 'white',
  text: 'black',
}

function renderSvg(option: EChartsCoreOption): string {
  use([...ECHARTS_MODULES, SVGRenderer])
  const chart = init(null, null, { renderer: 'svg', ssr: true, width: 400, height: 300 })
  chart.setOption(option)
  const svg = chart.renderToSVGString()
  chart.dispose()
  return svg
}

describe('tooltip theme', () => {
  it('tooltip_followsThemeColors', () => {
    const opt = incomeExpenseOption(
      { incomeCents: 1, expenseCents: 1 },
      colors,
      { income: 'a', expense: 'b' },
      'en',
    ) as { tooltip: Record<string, unknown> }
    expect(opt.tooltip).toMatchObject({
      backgroundColor: 'white',
      borderColor: 'silver',
      textStyle: { color: 'black' },
    })
  })
})

describe('chart options with the registered ECharts modules', () => {
  it('incomeExpenseOption_drawsBarsOnAxes', () => {
    const svg = renderSvg(
      incomeExpenseOption(
        { incomeCents: 500000, expenseCents: 123456 },
        colors,
        { income: 'Receitas', expense: 'Despesas' },
        'pt-BR',
      ),
    )
    expect(svg).toMatch(/<path|<rect/)
    expect(svg).toContain('green')
    expect(svg).toContain('red')
  })

  it('spendingOption_drawsDoughnutSlices', () => {
    const svg = renderSvg(
      spendingOption(
        [
          { categoryId: 'c1', name: 'Mercado', color: '#0ea5e9', icon: 'x', totalCents: 45000 },
          { categoryId: 'c2', name: 'Lazer', color: '#db2777', icon: 'x', totalCents: 12050 },
        ],
        colors,
        'pt-BR',
      ),
    )
    expect(svg).toContain('#0ea5e9')
    expect(svg).toContain('#db2777')
  })
})
