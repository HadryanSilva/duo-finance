import { defineComponent, h } from 'vue'

/**
 * Stand-in for `vue-echarts` in specs (mocked for every spec in setup.ts).
 * It renders no canvas; specs read the `option` prop of the `VChart` component.
 */
export default defineComponent({
  name: 'VChart',
  props: { option: { type: Object, required: true }, autoresize: Boolean },
  setup: () => () => h('div', { 'data-testid': 'chart' }),
})
