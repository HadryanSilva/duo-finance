<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { DashboardResponse, GoalResponse } from '@/shared/api/types'
import { currentMonth, formatMonth } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { Badge } from '@/shared/ui/badge'
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/shared/ui/card'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import CardEmpty from './CardEmpty.vue'

/** `undefined` while the dashboard loads; `month` is the dashboard's `YYYY-MM`. */
const props = defineProps<{ data?: DashboardResponse; month: string }>()

const { t, locale } = useI18n()

const goals = computed(() => props.data?.goals)

/** Goals with a monthly share need a salary to set a target from; an archived category is not usable. */
const needsSalaryCategory = computed(() => {
  const g = goals.value
  if (!g) return false
  const category = g.salaryCategory
  return g.goals.some((x) => x.monthlyPercent > 0) && (category === null || category.archived)
})

/**
 * Month targets still open: neither met nor skipped. Only for the running month: the Goals page,
 * where they are handled, works on the current month.
 */
const pending = computed(() => {
  if (props.month !== currentMonth()) return 0
  const open = (goals.value?.monthTargets ?? []).filter(
    (x) => !x.skipped && x.contributedCents < x.targetCents,
  )
  return open.length
})
const pendingMonth = computed(() => formatMonth(props.month, locale.value as Locale))

function percent(g: GoalResponse): number {
  if (g.targetCents <= 0) return 0
  return Math.min(100, Math.max(0, Math.floor((g.savedCents * 100) / g.targetCents)))
}
</script>

<template>
  <Card>
    <CardHeader>
      <CardTitle>{{ t('dashboard.goals.title') }}</CardTitle>
      <CardAction v-if="goals?.goals.length">
        <RouterLink
          to="/goals"
          :aria-label="t('dashboard.goals.viewAllLabel')"
          class="text-sm font-medium text-primary underline-offset-4 hover:underline"
        >
          {{ t('dashboard.goals.viewAll') }}
        </RouterLink>
      </CardAction>
    </CardHeader>
    <CardContent class="grid gap-4">
      <div v-if="!goals" class="grid gap-4">
        <Skeleton class="h-10 w-full" />
        <Skeleton class="h-10 w-full" />
      </div>
      <CardEmpty
        v-else-if="!goals.goals.length"
        :title="t('dashboard.goals.emptyTitle')"
        :description="t('dashboard.goals.emptyDescription')"
        :cta="t('dashboard.goals.emptyCta')"
        to="/goals?new=1"
      />
      <template v-else>
        <RouterLink
          v-if="pending"
          to="/goals"
          class="text-sm font-medium text-brand underline-offset-4 hover:underline"
        >
          {{ t('dashboard.goals.pending', { n: pending, month: pendingMonth }, pending) }}
        </RouterLink>
        <RouterLink
          v-else-if="needsSalaryCategory"
          to="/goals"
          class="text-sm text-brand underline-offset-4 hover:underline"
        >
          {{ t('dashboard.goals.salaryHint') }}
        </RouterLink>

        <ul class="grid gap-4" :aria-label="t('dashboard.goals.listLabel')">
          <li v-for="g in goals.goals" :key="g.id" class="grid gap-1">
            <div class="flex flex-wrap items-baseline justify-between gap-x-3 text-sm">
              <span class="flex min-w-0 items-center gap-2">
                <span class="truncate font-medium text-foreground">{{ g.name }}</span>
                <Badge v-if="g.status === 'REACHED'" variant="outline">
                  {{ t('goals.status.REACHED') }}
                </Badge>
              </span>
              <span class="flex items-baseline gap-x-3">
                <span class="text-muted-foreground">
                  <MoneyText :cents="g.savedCents" class="font-medium text-foreground" />
                  /
                  <MoneyText :cents="g.targetCents" />
                </span>
                <span class="font-medium tabular-nums text-foreground">{{ percent(g) }}%</span>
              </span>
            </div>
            <div
              role="progressbar"
              :aria-label="g.name"
              :aria-valuenow="percent(g)"
              aria-valuemin="0"
              aria-valuemax="100"
              :aria-valuetext="t('goals.progress', { percent: percent(g) })"
              class="h-2 w-full overflow-hidden rounded-full bg-muted"
            >
              <div class="h-full rounded-full bg-brand" :style="{ width: `${percent(g)}%` }" />
            </div>
          </li>
        </ul>
      </template>
    </CardContent>
  </Card>
</template>
