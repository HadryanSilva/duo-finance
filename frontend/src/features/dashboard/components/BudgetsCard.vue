<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { BudgetResponse, DashboardResponse } from '@/shared/api/types'
import { todayIso } from '@/shared/lib/dates'
import BudgetProgressBar from '@/shared/ui/BudgetProgressBar.vue'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/card'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import { budgetRemainder, daysLeftInMonth } from '../lib/figures'
import CardEmpty from './CardEmpty.vue'

/** `undefined` while the dashboard loads; `month` is the dashboard's `YYYY-MM`. */
const props = defineProps<{ data?: DashboardResponse; month: string }>()

const { t } = useI18n()

/** Only the running month has days left; a past or future month shows none. */
const daysLeft = computed(() => daysLeftInMonth(props.month, todayIso()))

function remainder(b: BudgetResponse) {
  const r = budgetRemainder(b.spentCents, b.monthlyLimitCents)
  const color =
    r.over || b.status === 'EXCEEDED'
      ? 'text-expense'
      : b.status === 'WARNING'
        ? 'text-warning'
        : 'text-foreground'
  return { ...r, color }
}
</script>

<template>
  <Card>
    <CardHeader class="flex-row flex-wrap items-baseline justify-between gap-x-3">
      <CardTitle>{{ t('dashboard.budgets.title') }}</CardTitle>
      <p v-if="data?.budgets.length && daysLeft !== null" class="text-sm text-muted-foreground">
        {{
          daysLeft === 1
            ? t('dashboard.budgets.lastDay')
            : t('dashboard.budgets.daysLeft', { n: daysLeft }, daysLeft)
        }}
      </p>
    </CardHeader>
    <CardContent>
      <div v-if="!data" class="grid gap-4">
        <Skeleton class="h-10 w-full" />
        <Skeleton class="h-10 w-full" />
      </div>
      <CardEmpty
        v-else-if="!data.budgets.length"
        :title="t('dashboard.budgets.emptyTitle')"
        :description="t('dashboard.budgets.emptyDescription')"
        :cta="t('dashboard.budgets.emptyCta')"
        to="/budgets"
      />
      <ul v-else class="grid gap-4" :aria-label="t('dashboard.budgets.listLabel')">
        <li v-for="b in data.budgets" :key="b.id" class="grid gap-1">
          <div class="flex flex-wrap items-baseline justify-between gap-x-3 text-sm">
            <span class="min-w-0 truncate font-medium text-foreground">{{ b.category.name }}</span>
            <i18n-t
              :keypath="
                remainder(b).over ? 'dashboard.budgets.over' : 'dashboard.budgets.remaining'
              "
              tag="p"
              class="font-medium"
              :class="remainder(b).color"
            >
              <template #amount><MoneyText :cents="remainder(b).amountCents" /></template>
            </i18n-t>
          </div>
          <BudgetProgressBar
            :spent-cents="b.spentCents"
            :limit-cents="b.monthlyLimitCents"
            :percent="b.percent"
            :status="b.status"
            :label="b.category.name"
          />
        </li>
      </ul>
    </CardContent>
  </Card>
</template>
