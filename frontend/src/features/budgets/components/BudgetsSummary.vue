<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { BudgetResponse, BudgetStatus } from '@/shared/api/types'
import { todayIso } from '@/shared/lib/dates'
import { budgetRemainder, daysLeftInMonth } from '@/shared/lib/figures'
import BudgetProgressBar from '@/shared/ui/BudgetProgressBar.vue'
import MoneyText from '@/shared/ui/MoneyText.vue'

/** The month's budgets (at least one) and that month, `YYYY-MM`. */
const props = defineProps<{ budgets: BudgetResponse[]; month: string }>()

const { t } = useI18n()

/** Same thresholds as the backend's BudgetStatus.of, applied to the month's total. */
const WARNING_PERCENT = 80
const EXCEEDED_PERCENT = 100

const spent = computed(() => props.budgets.reduce((sum, b) => sum + b.spentCents, 0))
const limit = computed(() => props.budgets.reduce((sum, b) => sum + b.monthlyLimitCents, 0))
/** Truncated, as the backend's BudgetResponse.percent: 99.5% is still 99%, not exceeded. */
const percent = computed(() =>
  limit.value > 0 ? Math.floor((spent.value * 100) / limit.value) : 0,
)
const status = computed<BudgetStatus>(() =>
  percent.value >= EXCEEDED_PERCENT
    ? 'EXCEEDED'
    : percent.value >= WARNING_PERCENT
      ? 'WARNING'
      : 'NORMAL',
)
const remainder = computed(() => budgetRemainder(spent.value, limit.value))
const exceeded = computed(() => props.budgets.filter((b) => b.status === 'EXCEEDED').length)
/** Only the running month has days left. */
const daysLeft = computed(() => daysLeftInMonth(props.month, todayIso()))
</script>

<template>
  <section class="mb-4 grid gap-3 rounded-lg border border-border bg-card p-4">
    <div class="flex flex-wrap items-baseline justify-between gap-x-3">
      <h2 class="font-semibold text-foreground">{{ t('budgets.summary.title') }}</h2>
      <p v-if="daysLeft !== null" class="text-sm text-muted-foreground">
        {{
          daysLeft === 1
            ? t('budgets.summary.lastDay')
            : t('budgets.summary.daysLeft', { n: daysLeft }, daysLeft)
        }}
      </p>
    </div>
    <BudgetProgressBar
      :spent-cents="spent"
      :limit-cents="limit"
      :percent="percent"
      :status="status"
      :label="t('budgets.summary.title')"
    />
    <div class="flex flex-wrap items-baseline justify-between gap-x-3 text-sm">
      <i18n-t
        :keypath="remainder.over ? 'budgets.summary.over' : 'budgets.summary.remaining'"
        tag="p"
        :class="remainder.over ? 'text-expense' : 'text-muted-foreground'"
      >
        <template #amount><MoneyText :cents="remainder.amountCents" /></template>
      </i18n-t>
      <p v-if="exceeded > 0" class="font-medium text-expense">
        {{ t('budgets.summary.exceeded', { n: exceeded }, exceeded) }}
      </p>
    </div>
  </section>
</template>
