<script setup lang="ts">
import type { EChartsCoreOption } from 'echarts/core'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DashboardResponse } from '@/shared/api/types'
import type { Locale } from '@/shared/lib/money'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/card'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import { useChartColors } from '../lib/chart-colors'
import { spendingOption } from '../lib/chart-options'
import { LazyChart } from './lazy-chart'

/** `undefined` while the dashboard loads. */
const props = defineProps<{ data?: DashboardResponse }>()

const { t, locale } = useI18n()
const colors = useChartColors()

const spending = computed(() => props.data?.spendingByCategory ?? [])

const option = computed<EChartsCoreOption>(() =>
  spendingOption(spending.value, colors.value, locale.value as Locale),
)
</script>

<template>
  <Card>
    <CardHeader>
      <CardTitle>{{ t('dashboard.spending.title') }}</CardTitle>
    </CardHeader>
    <CardContent>
      <div v-if="!data" class="grid gap-4">
        <Skeleton class="h-48 w-full" />
        <Skeleton class="h-5 w-full" />
      </div>
      <p v-else-if="!spending.length" class="text-sm text-muted-foreground">
        {{ t('dashboard.spending.empty') }}
      </p>
      <div v-else class="grid gap-4">
        <div role="img" :aria-label="t('dashboard.spending.chartLabel')" class="h-48 w-full">
          <LazyChart :option="option" />
        </div>
        <ul class="grid gap-2" :aria-label="t('dashboard.spending.legendLabel')">
          <li
            v-for="c in spending"
            :key="c.categoryId"
            class="flex items-center justify-between gap-3 text-sm"
          >
            <span class="flex min-w-0 items-center gap-2">
              <span
                class="size-3 shrink-0 rounded-full"
                :style="{ backgroundColor: c.color }"
                aria-hidden="true"
              />
              <span class="truncate text-foreground">{{ c.name }}</span>
            </span>
            <MoneyText :cents="c.totalCents" class="shrink-0 font-medium" />
          </li>
        </ul>
      </div>
    </CardContent>
  </Card>
</template>
