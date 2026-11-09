<script setup lang="ts">
import { use, type EChartsCoreOption } from 'echarts/core'
import VChart from 'vue-echarts'
import { ECHARTS_MODULES } from '../lib/echarts-modules'

use(ECHARTS_MODULES)

defineProps<{ option: EChartsCoreOption }>()
</script>

<template>
  <VChart :option="option" autoresize class="h-full w-full" />
</template>
