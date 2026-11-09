import { BarChart, PieChart } from 'echarts/charts'
import { GridComponent, TooltipComponent } from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'

/**
 * Only what the dashboard draws is registered, which keeps the ECharts chunk small. The cartesian
 * grid and axes are not part of BarChart; the legend is HTML, so there is no LegendComponent.
 */
export const ECHARTS_MODULES = [BarChart, PieChart, GridComponent, TooltipComponent, CanvasRenderer]
