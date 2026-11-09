<script setup lang="ts">
// Only what the dashboard draws is registered, which keeps the ECharts chunk small.
import { BarChart, PieChart } from 'echarts/charts'
import { LegendComponent, TooltipComponent } from 'echarts/components'
import { use, type EChartsCoreOption } from 'echarts/core'
import { CanvasRenderer } from 'echarts/renderers'
import VChart from 'vue-echarts'

use([BarChart, PieChart, TooltipComponent, LegendComponent, CanvasRenderer])

defineProps<{ option: EChartsCoreOption }>()
</script>

<template>
  <VChart :option="option" autoresize class="h-full w-full" />
</template>
