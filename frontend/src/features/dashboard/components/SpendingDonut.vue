<script setup lang="ts">
import type { EChartsCoreOption } from 'echarts/core'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { DashboardResponse } from '@/shared/api/types'
import { monthRange } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/card'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import { useChartColors } from '../lib/chart-colors'
import { spendingOption } from '../lib/chart-options'
import { shareLabel } from '@/shared/lib/figures'
import { moneyLabel, spendingSlices, type SpendingSlice } from '../lib/figures'
import CardEmpty from './CardEmpty.vue'
import { LazyChart } from './lazy-chart'

/** `undefined` while the dashboard loads; `month` is the dashboard's `YYYY-MM`. */
const props = defineProps<{ data?: DashboardResponse; month: string }>()

const { t, locale } = useI18n()
const colors = useChartColors()

const slices = computed(() => spendingSlices(props.data?.spendingByCategory ?? []))
const othersName = (n: number) => t('dashboard.spending.others', { n })
const nameOf = (s: SpendingSlice) => s.name ?? othersName(s.othersCount)
const share = (s: SpendingSlice) => shareLabel(s.share, s.totalCents)

/** The month's transactions of one category: the Transactions page reads these query keys. */
const transactionsOf = (categoryId: string) => ({
  path: '/transactions',
  query: { ...monthRange(props.month), categoryId },
})

const option = computed<EChartsCoreOption>(() =>
  spendingOption(slices.value, colors.value, locale.value as Locale, othersName),
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
      <CardEmpty
        v-else-if="!slices.length"
        :title="t('dashboard.spending.empty')"
        :description="t('dashboard.spending.emptyDescription')"
        :cta="t('dashboard.spending.emptyCta')"
        to="/transactions?new=1"
      />
      <div v-else class="grid items-center gap-4 sm:grid-cols-5">
        <div
          role="img"
          :aria-label="t('dashboard.spending.chartLabel')"
          class="h-48 w-full sm:col-span-2"
        >
          <LazyChart :option="option" />
        </div>
        <ul class="grid gap-1 sm:col-span-3" :aria-label="t('dashboard.spending.legendLabel')">
          <li v-for="s in slices" :key="s.key">
            <component
              :is="s.categoryId ? RouterLink : 'div'"
              v-bind="
                s.categoryId
                  ? {
                      to: transactionsOf(s.categoryId),
                      'aria-label': t('dashboard.spending.linkLabel', {
                        name: nameOf(s),
                        amount: moneyLabel(s.totalCents, locale as Locale),
                        share: share(s),
                      }),
                    }
                  : {}
              "
              class="-mx-2 flex items-center justify-between gap-3 rounded-md px-2 py-1.5 text-sm"
              :class="
                s.categoryId &&
                'transition-colors hover:bg-accent focus-visible:outline-2 focus-visible:outline-ring'
              "
            >
              <span class="flex min-w-0 items-center gap-2">
                <span
                  class="size-3 shrink-0 rounded-full"
                  :class="s.color ? '' : 'bg-muted-foreground'"
                  :style="s.color ? { backgroundColor: s.color } : undefined"
                  aria-hidden="true"
                />
                <span class="truncate text-foreground">{{ nameOf(s) }}</span>
              </span>
              <span class="flex shrink-0 items-baseline gap-3">
                <MoneyText :cents="s.totalCents" class="font-medium" />
                <span class="w-10 text-right tabular-nums text-muted-foreground">{{
                  share(s)
                }}</span>
              </span>
            </component>
          </li>
        </ul>
      </div>
    </CardContent>
  </Card>
</template>
