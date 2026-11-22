<script setup lang="ts">
import { Check } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import type { BudgetResponse } from '@/shared/api/types'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { Skeleton } from '@/shared/ui/skeleton'

/** A category's current budget and how the reviewed month did against it (status from the API, as on Orçamentos). */
defineProps<{ budget: BudgetResponse | null; loading: boolean }>()

const { t } = useI18n()
</script>

<template>
  <Skeleton v-if="loading" class="ml-auto h-4 w-16" />
  <span v-else-if="!budget" class="text-muted-foreground">—</span>
  <span v-else class="inline-flex flex-wrap items-center justify-end gap-x-2">
    <MoneyText :cents="budget.monthlyLimitCents" />
    <span v-if="budget.status === 'EXCEEDED'" class="text-expense">{{
      t('review.budgetExceeded')
    }}</span>
    <span v-else-if="budget.status === 'WARNING'" class="text-warning">{{
      t('review.budgetWarning')
    }}</span>
    <span v-else class="inline-flex items-center text-income">
      <Check class="size-4" aria-hidden="true" />
      <span class="sr-only">{{ t('review.budgetNormal') }}</span>
    </span>
  </span>
</template>
