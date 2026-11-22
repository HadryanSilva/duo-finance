<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink, useRoute, useRouter } from 'vue-router'
import BudgetForm from '@/shared/ui/BudgetForm.vue'
import { useBudgets } from '@/shared/api/queries/budgets'
import type { BudgetResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import EmptyState from '@/shared/ui/EmptyState.vue'
import MonthSelector from '@/shared/ui/MonthSelector.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import SidePanel from '@/shared/ui/SidePanel.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import { useMonthReview } from '../api/review-api'
import ReviewCategories from '../components/ReviewCategories.vue'
import ReviewSummary from '../components/ReviewSummary.vue'
import { clampMonth, hasClosedMonth, suggestedLimit, type ReviewRow } from '../lib/review'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

/**
 * Which months can be reviewed is only known from the server (the last closed one depends on the clock there), so
 * the first request asks for its default month; after that the URL's month, kept within those limits, is shown.
 */
const bounds = ref<{ first: string | null; last: string } | null>(null)
const month = computed(() =>
  bounds.value ? clampMonth(route.query.month, bounds.value.first, bounds.value.last) : undefined,
)
const review = useMonthReview(month)
// Today's budgets judged against the reviewed month's spending, so a past month shows the current limits.
const budgets = useBudgets(
  () => month.value ?? '',
  () => month.value !== undefined,
)

// Side panel: the row whose budget is being set or adjusted, with that budget (null when it has none).
const panel = ref<{ row: ReviewRow; budget: BudgetResponse | null } | null>(null)
const panelOpen = computed({
  get: () => panel.value !== null,
  set: (open: boolean) => {
    if (!open) panel.value = null
  },
})
/** Where the suggested limit comes from is said next to it, so it reads as a reference, not advice. */
const suggestion = computed(() => {
  if (!panel.value) return undefined
  const s = suggestedLimit(panel.value.row)
  const n = review.data.value?.comparedMonths ?? 0
  return {
    cents: s.cents,
    label:
      s.source === 'average'
        ? t('review.suggestionAverage', { n }, n)
        : t('review.suggestionMonth'),
  }
})

watch(
  () => review.data.value,
  (data) => {
    if (data) bounds.value = { first: data.firstMonth, last: data.lastClosedMonth }
  },
  { immediate: true },
)
// The URL holds the month, so a shared link shows the same review; one out of range is corrected.
watch(month, (m) => {
  if (m && route.query.month !== m) void router.replace({ query: { ...route.query, month: m } })
})

const selected = computed({
  get: () => month.value ?? '',
  set: (value: string) => void router.replace({ query: { ...route.query, month: value } }),
})
</script>

<template>
  <PageHeader :title="t('review.title')" :subtitle="t('review.subtitle')" />

  <template v-if="!bounds">
    <p v-if="review.isError.value" role="alert" class="mb-4 text-sm text-destructive">
      {{ errorMessage(review.error.value) }}
    </p>
    <div v-else class="grid gap-4">
      <Skeleton class="h-24 w-full" />
      <Skeleton class="h-64 w-full" />
    </div>
  </template>
  <EmptyState
    v-else-if="!hasClosedMonth(bounds.first, bounds.last)"
    :title="t('review.empty')"
    :description="t('review.emptyDescription')"
  >
    <Button as-child variant="outline" size="sm">
      <RouterLink to="/transactions/import">{{ t('review.emptyCta') }}</RouterLink>
    </Button>
  </EmptyState>
  <template v-else>
    <!-- Outside the loading and error states, so changing month keeps the focus on the arrows. -->
    <div class="mb-4">
      <MonthSelector v-model="selected" :min="bounds.first ?? undefined" :max="bounds.last" />
    </div>
    <p v-if="review.isError.value" role="alert" class="mb-4 text-sm text-destructive">
      {{ errorMessage(review.error.value) }}
    </p>
    <div
      v-else-if="review.data.value"
      class="grid gap-4 transition-opacity"
      :class="review.isPlaceholderData.value && 'opacity-60'"
      :aria-busy="review.isPlaceholderData.value"
    >
      <ReviewSummary :data="review.data.value" />
      <ReviewCategories
        :data="review.data.value"
        :budgets="budgets.data.value"
        :budgets-error="budgets.isError.value"
        @budget="(row, budget) => (panel = { row, budget })"
      />
    </div>
    <div v-else class="grid gap-4">
      <Skeleton class="h-24 w-full" />
      <Skeleton class="h-64 w-full" />
    </div>
  </template>

  <SidePanel
    v-model:open="panelOpen"
    :title="panel?.budget ? t('budgets.editTitle') : t('budgets.createTitle')"
  >
    <BudgetForm
      v-if="panel"
      :key="panel.row.categoryId"
      :budget="panel.budget ?? undefined"
      :category="{ id: panel.row.categoryId, name: panel.row.name }"
      :suggestion="suggestion"
      @done="panelOpen = false"
    />
  </SidePanel>
</template>
