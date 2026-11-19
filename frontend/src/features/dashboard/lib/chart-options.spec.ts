import { init, use, type EChartsCoreOption } from 'echarts/core'
import { SVGRenderer } from 'echarts/renderers'
import { describe, expect, it, vi } from 'vitest'
import type { ChartColors } from './chart-colors'
import { spendingSlices } from './figures'
import { spendingOption } from './chart-options'
import { ECHARTS_MODULES } from './echarts-modules'

// The global mock in test/setup.ts hides missing registrations: use the real ECharts here.
vi.unmock('echarts/core')
vi.unmock('echarts/charts')
vi.unmock('echarts/components')
vi.unmock('echarts/renderers')

const others = (n: number) => `Outras ${n}`
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
    const opt = spendingOption([], colors, 'en', others) as { tooltip: Record<string, unknown> }
    expect(opt.tooltip).toMatchObject({
      backgroundColor: 'white',
      borderColor: 'silver',
      textStyle: { color: 'black' },
    })
  })
})

describe('tooltip text', () => {
  type Formatter = (p: { name: string; value: number }) => string
  const formatterOf = (opt: unknown) =>
    (opt as { tooltip: { formatter: Formatter } }).tooltip.formatter

  it('spendingTooltip_escapesUserEnteredNames', () => {
    const name = '<img src=x onerror=alert(1)>'
    const opt = spendingOption(
      spendingSlices([{ categoryId: 'c1', name, color: '#0ea5e9', icon: 'x', totalCents: 1250 }]),
      colors,
      'en',
      others,
    )
    const html = formatterOf(opt)({ name, value: 1250 })
    expect(html).not.toContain('<img')
    expect(html).toContain('&lt;img src=x onerror=alert(1)&gt;')
  })
})

describe('chart options with the registered ECharts modules', () => {
  it('spendingOption_drawsDoughnutSlices', () => {
    const svg = renderSvg(
      spendingOption(
        spendingSlices([
          { categoryId: 'c1', name: 'Mercado', color: '#0ea5e9', icon: 'x', totalCents: 45000 },
          { categoryId: 'c2', name: 'Lazer', color: '#db2777', icon: 'x', totalCents: 12050 },
        ]),
        colors,
        'pt-BR',
        others,
      ),
    )
    expect(svg).toContain('#0ea5e9')
    expect(svg).toContain('#db2777')
  })
})

describe('spendingOption', () => {
  it('othersSlice_isNamedAndMuted', () => {
    const seven = [1, 2, 3, 4, 5, 6, 7].map((i) => ({
      categoryId: 'c' + i,
      name: 'C' + i,
      color: '#0ea5e9',
      icon: 'x',
      totalCents: 1000,
    }))
    const opt = spendingOption(spendingSlices(seven), colors, 'en', others) as {
      series: { data: { name: string; itemStyle: { color: string } }[] }[]
    }
    const last = opt.series[0]!.data[5]!
    expect(last.name).toBe('Outras 2')
    expect(last.itemStyle.color).toBe('gray')
  })
})
