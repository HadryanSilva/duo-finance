import { afterAll, afterEach, beforeAll, vi } from 'vitest'
import { server } from './msw'
import { cleanup } from './render'

// ECharts draws on a canvas, which jsdom lacks, and loading it cold is slow. Specs read the
// `option` prop of the stubbed VChart instead.
vi.mock('vue-echarts', () => import('./echarts-stub'))
vi.mock('echarts/core', () => ({ use: () => {} }))
vi.mock('echarts/charts', () => ({ BarChart: {}, PieChart: {} }))
vi.mock('echarts/components', () => ({ TooltipComponent: {}, LegendComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
  localStorage.clear()
})
afterAll(() => server.close())
