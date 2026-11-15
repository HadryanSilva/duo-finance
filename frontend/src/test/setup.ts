import { afterAll, afterEach, beforeAll, vi } from 'vitest'
import { server } from './msw'
import { cleanup } from './render'

// ECharts draws on a canvas, which jsdom lacks, and loading it cold is slow. Specs read the
// `option` prop of the stubbed VChart instead.
vi.mock('vue-echarts', () => import('./echarts-stub'))
vi.mock('echarts/core', () => ({ use: () => {} }))
vi.mock('echarts/charts', () => ({ BarChart: {}, PieChart: {} }))
vi.mock('echarts/components', () => ({ GridComponent: {}, TooltipComponent: {} }))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))

// The router's scrollBehavior scrolls on every navigation; jsdom does not implement scrolling.
window.scrollTo = () => {}

// jsdom has no matchMedia. Answer `(min-width: Nrem)` from window.innerWidth (jsdom's default 1024,
// so specs run as desktop unless they set a phone width); any other query does not match.
function matchMedia(media: string): MediaQueryList {
  const rem = /\(min-width:\s*([\d.]+)rem\)/.exec(media)?.[1]
  return {
    media,
    matches: rem !== undefined && window.innerWidth >= Number(rem) * 16,
    onchange: null,
    addEventListener: () => {},
    removeEventListener: () => {},
    addListener: () => {},
    removeListener: () => {},
    dispatchEvent: () => false,
  }
}
Object.defineProperty(window, 'matchMedia', {
  configurable: true,
  writable: true,
  value: matchMedia,
})

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
  localStorage.clear()
})
afterAll(() => server.close())
