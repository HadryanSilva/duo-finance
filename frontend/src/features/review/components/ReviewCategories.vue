<script setup lang="ts">
import { Pencil, Plus } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { BudgetResponse, MonthReviewResponse } from '@/shared/api/types'
import { monthRange } from '@/shared/lib/dates'
import { swatchInkClass } from '@/shared/lib/contrast'
import ArchivedBadge from '@/shared/ui/ArchivedBadge.vue'
import { Button } from '@/shared/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/card'
import CategoryIcon from '@/shared/ui/CategoryIcon.vue'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { reviewRows, shareText, type ReviewOrder, type ReviewRow } from '../lib/review'
import DifferenceText from './DifferenceText.vue'
import ReviewBudgetCell from './ReviewBudgetCell.vue'

const props = defineProps<{
  data: MonthReviewResponse
  /** The reviewed month's budgets; undefined while they load or when they failed. */
  budgets: BudgetResponse[] | undefined
  budgetsError: boolean
}>()
const emit = defineEmits<{ budget: [row: ReviewRow, budget: BudgetResponse | null] }>()

const { t } = useI18n()
const compared = computed(() => props.data.comparedMonths > 0)
/** What cost most first by default; without a comparison there is no change to order by. */
const order = ref<ReviewOrder>('size')
const rows = computed(() =>
  reviewRows(
    props.data.categories,
    props.data.expense.cents,
    compared.value ? order.value : 'size',
  ),
)

const budgetOf = computed(() => new Map((props.budgets ?? []).map((b) => [b.category.id, b])))
const budgetsLoading = computed(() => props.budgets === undefined && !props.budgetsError)

/** The month's transactions of one category: the Transactions page reads these query keys. */
const transactionsOf = (categoryId: string) => ({
  path: '/transactions',
  query: { ...monthRange(props.data.month), categoryId },
})

const ORDERS: { value: ReviewOrder; label: string }[] = [
  { value: 'size', label: 'review.orderSize' },
  { value: 'change', label: 'review.orderChange' },
]
</script>

<template>
  <Card>
    <CardHeader class="flex flex-row flex-wrap items-center justify-between gap-2">
      <CardTitle>{{ t('review.whereItWent') }}</CardTitle>
      <div
        v-if="compared && rows.length"
        role="group"
        :aria-label="t('review.orderLabel')"
        class="inline-flex rounded-md border border-border p-0.5"
      >
        <Button
          v-for="o in ORDERS"
          :key="o.value"
          type="button"
          size="xs"
          :variant="order === o.value ? 'secondary' : 'ghost'"
          :aria-pressed="order === o.value"
          @click="order = o.value"
        >
          {{ t(o.label) }}
        </Button>
      </div>
    </CardHeader>
    <CardContent>
      <p v-if="!rows.length" class="text-sm text-muted-foreground">{{ t('review.noSpending') }}</p>
      <template v-else>
        <table class="w-full text-sm">
          <thead>
            <tr class="text-left text-muted-foreground">
              <th scope="col" class="py-2 font-normal">{{ t('review.category') }}</th>
              <th scope="col" class="py-2 text-right font-normal">{{ t('review.thisMonth') }}</th>
              <th scope="col" class="hidden py-2 text-right font-normal sm:table-cell">
                {{ t('review.share') }}
              </th>
              <template v-if="compared">
                <th scope="col" class="hidden py-2 text-right font-normal sm:table-cell">
                  {{ t('review.average', { n: data.comparedMonths }, data.comparedMonths) }}
                </th>
                <th scope="col" class="py-2 text-right font-normal">
                  {{ t('review.difference') }}
                </th>
              </template>
              <th
                scope="col"
                class="hidden py-2 text-right font-normal sm:table-cell"
                :title="t('review.budgetHint')"
              >
                {{ t('review.budget') }}
                <span class="sr-only">({{ t('review.budgetHint') }})</span>
              </th>
              <th scope="col" class="py-2">
                <span class="sr-only">{{ t('review.actions') }}</span>
              </th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="r in rows" :key="r.categoryId" class="border-t border-border">
              <th scope="row" class="py-2 text-left font-normal">
                <RouterLink
                  :to="transactionsOf(r.categoryId)"
                  class="-mx-2 inline-flex min-w-0 items-center gap-2 rounded-md px-2 py-1 text-foreground transition-colors hover:bg-accent focus-visible:outline-2 focus-visible:outline-ring"
                >
                  <span
                    class="flex size-6 shrink-0 items-center justify-center rounded-full"
                    :class="swatchInkClass(r.color)"
                    :style="{ backgroundColor: r.color }"
                  >
                    <!-- the color comes from the API as data, not from the design tokens -->
                    <CategoryIcon :name="r.icon" />
                  </span>
                  <span class="truncate">{{ r.name }}</span>
                  <ArchivedBadge v-if="r.archived" />
                </RouterLink>
                <!-- On a phone the row has no room for a budget column: the budget goes under the name. -->
                <div
                  v-if="!budgetsError && (budgetsLoading || budgetOf.has(r.categoryId))"
                  class="pl-8 text-xs sm:hidden"
                >
                  <ReviewBudgetCell
                    :budget="budgetOf.get(r.categoryId) ?? null"
                    :loading="budgetsLoading"
                    align="start"
                  />
                </div>
              </th>
              <td class="py-2 text-right whitespace-nowrap">
                <MoneyText :cents="r.cents" class="font-medium" />
              </td>
              <td class="hidden py-2 text-right text-muted-foreground sm:table-cell">
                {{ shareText(r) }}
              </td>
              <template v-if="compared">
                <td class="hidden py-2 text-right text-muted-foreground sm:table-cell">
                  <MoneyText v-if="r.averageCents !== null" :cents="r.averageCents" />
                </td>
                <td class="py-2 pl-2 text-right whitespace-nowrap">
                  <DifferenceText
                    v-if="r.differenceCents !== null && r.tone"
                    :cents="r.differenceCents"
                    :tone="r.tone"
                  />
                </td>
              </template>
              <td class="hidden py-2 pl-2 text-right sm:table-cell">
                <ReviewBudgetCell
                  v-if="!budgetsError"
                  :budget="budgetOf.get(r.categoryId) ?? null"
                  :loading="budgetsLoading"
                />
              </td>
              <td class="py-2 pl-2 text-right">
                <!-- Archived categories cannot get a budget (the API refuses them). -->
                <Button
                  v-if="!r.archived && !budgetsLoading && !budgetsError"
                  type="button"
                  variant="ghost"
                  size="sm"
                  :aria-label="
                    t(
                      budgetOf.has(r.categoryId)
                        ? 'review.adjustBudgetLabel'
                        : 'review.setBudgetLabel',
                      {
                        name: r.name,
                      },
                    )
                  "
                  @click="emit('budget', r, budgetOf.get(r.categoryId) ?? null)"
                >
                  <Pencil v-if="budgetOf.has(r.categoryId)" class="size-4" aria-hidden="true" />
                  <Plus v-else class="size-4" aria-hidden="true" />
                  <span class="hidden sm:inline">{{
                    t(budgetOf.has(r.categoryId) ? 'review.adjustBudget' : 'review.setBudget')
                  }}</span>
                </Button>
              </td>
            </tr>
          </tbody>
        </table>
        <p v-if="budgetsError" class="mt-3 text-sm text-muted-foreground">
          {{ t('review.budgetError') }}
        </p>
      </template>
    </CardContent>
  </Card>
</template>
