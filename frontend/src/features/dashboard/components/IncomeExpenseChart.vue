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
import { incomeExpenseOption } from '../lib/chart-options'
import { LazyChart } from './lazy-chart'

/** `undefined` while the dashboard loads. */
const props = defineProps<{ data?: DashboardResponse }>()

const { t, locale } = useI18n()
const colors = useChartColors()

const option = computed<EChartsCoreOption | null>(() =>
  props.data
    ? incomeExpenseOption(
        props.data,
        colors.value,
        {
          income: t('dashboard.incomeExpense.income'),
          expense: t('dashboard.incomeExpense.expense'),
        },
        locale.value as Locale,
      )
    : null,
)
</script>

<template>
  <Card>
    <CardHeader>
      <CardTitle>{{ t('dashboard.incomeExpense.title') }}</CardTitle>
    </CardHeader>
    <CardContent class="grid gap-4">
      <div v-if="!data" class="grid gap-4">
        <Skeleton class="h-10 w-full" />
        <Skeleton class="h-48 w-full" />
      </div>
      <template v-else>
        <dl class="grid grid-cols-2 gap-4">
          <div>
            <dt class="text-sm text-muted-foreground">{{ t('dashboard.incomeExpense.income') }}</dt>
            <dd class="text-xl font-semibold">
              <MoneyText :cents="data.incomeCents" kind="INCOME" />
            </dd>
          </div>
          <div>
            <dt class="text-sm text-muted-foreground">
              {{ t('dashboard.incomeExpense.expense') }}
            </dt>
            <dd class="text-xl font-semibold">
              <MoneyText :cents="data.expenseCents" kind="EXPENSE" />
            </dd>
          </div>
        </dl>
        <div role="img" :aria-label="t('dashboard.incomeExpense.chartLabel')" class="h-48 w-full">
          <LazyChart v-if="option" :option="option" />
        </div>
      </template>
    </CardContent>
  </Card>
</template>
