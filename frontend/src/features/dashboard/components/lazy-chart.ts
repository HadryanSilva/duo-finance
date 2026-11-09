import { defineAsyncComponent } from 'vue'
import ChartLoading from './ChartLoading.vue'

/** ECharts is a separate chunk, fetched the first time a chart is shown. */
export const LazyChart = defineAsyncComponent({
  loader: () => import('./EChart.vue'),
  loadingComponent: ChartLoading,
  delay: 0,
})
