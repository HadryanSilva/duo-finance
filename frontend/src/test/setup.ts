import { afterAll, afterEach, beforeAll, vi } from 'vitest'
import { server } from './msw'
import { cleanup } from './render'
import { installMatchMediaStub, resetViewport } from './viewport'

// ECharts draws on a canvas, which jsdom lacks, and loading it cold is slow. Specs read the
// `option` prop of the stubbed VChart instead.
vi.mock('vue-echarts', () => import('./echarts-stub'))
vi.mock('echarts/core', () => ({ use: () => {} }))
vi.mock('echarts/charts', () => ({ PieChart: {} }))
vi.mock('echarts/components', () => ({ TooltipComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

// The router's scrollBehavior scrolls on every navigation; jsdom does not implement scrolling.
window.scrollTo = () => {}

installMatchMediaStub()

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  cleanup()
  resetViewport()
  server.resetHandlers()
  localStorage.clear()
})
afterAll(() => server.close())
