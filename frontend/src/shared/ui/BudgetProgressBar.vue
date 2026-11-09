<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { BudgetStatus } from '@/shared/api/types'
import MoneyText from '@/shared/ui/MoneyText.vue'

const props = defineProps<{
  spentCents: number
  limitCents: number
  /** Real percentage; may exceed 100. */
  percent: number
  status: BudgetStatus
  label?: string
}>()

const { t } = useI18n()

/** The bar stops at 100%; the text keeps the real value. */
const width = computed(() => Math.min(Math.max(props.percent, 0), 100))

const FILL: Record<BudgetStatus, string> = {
  NORMAL: 'bg-brand',
  WARNING: 'bg-warning',
  EXCEEDED: 'bg-expense',
}
const TEXT: Record<BudgetStatus, string> = {
  NORMAL: 'text-foreground',
  WARNING: 'text-warning',
  EXCEEDED: 'text-expense',
}
</script>

<template>
  <div class="grid gap-2">
    <div class="flex flex-wrap items-baseline justify-between gap-x-3 text-sm">
      <span class="text-muted-foreground">
        <MoneyText :cents="spentCents" class="font-medium text-foreground" />
        {{ t('budgets.of') }}
        <MoneyText :cents="limitCents" />
      </span>
      <span class="font-medium tabular-nums" :class="TEXT[status]">{{ percent }}%</span>
    </div>
    <div
      role="progressbar"
      :aria-label="label"
      :aria-valuenow="width"
      aria-valuemin="0"
      aria-valuemax="100"
      :aria-valuetext="t('budgets.progress', { percent })"
      :data-status="status"
      class="h-2 w-full overflow-hidden rounded-full bg-muted"
    >
      <div class="h-full rounded-full" :class="FILL[status]" :style="{ width: `${width}%` }" />
    </div>
  </div>
</template>
