<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { MonthReviewResponse } from '@/shared/api/types'
import { monthRange } from '@/shared/lib/dates'
import { swatchInkClass } from '@/shared/lib/contrast'
import ArchivedBadge from '@/shared/ui/ArchivedBadge.vue'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/card'
import CategoryIcon from '@/shared/ui/CategoryIcon.vue'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { reviewRows } from '../lib/review'
import DifferenceText from './DifferenceText.vue'

const props = defineProps<{ data: MonthReviewResponse }>()

const { t } = useI18n()
const rows = computed(() => reviewRows(props.data.categories))
const compared = computed(() => props.data.comparedMonths > 0)

/** The month's transactions of one category: the Transactions page reads these query keys. */
const transactionsOf = (categoryId: string) => ({
  path: '/transactions',
  query: { ...monthRange(props.data.month), categoryId },
})
</script>

<template>
  <Card>
    <CardHeader>
      <CardTitle>{{ t('review.whereItWent') }}</CardTitle>
    </CardHeader>
    <CardContent>
      <p v-if="!rows.length" class="text-sm text-muted-foreground">{{ t('review.noSpending') }}</p>
      <table v-else class="w-full text-sm">
        <thead>
          <tr class="text-left text-muted-foreground">
            <th scope="col" class="py-2 font-normal">{{ t('review.category') }}</th>
            <th scope="col" class="py-2 text-right font-normal">{{ t('review.thisMonth') }}</th>
            <template v-if="compared">
              <th scope="col" class="hidden py-2 text-right font-normal sm:table-cell">
                {{ t('review.average', { n: data.comparedMonths }, data.comparedMonths) }}
              </th>
              <th scope="col" class="py-2 text-right font-normal">{{ t('review.difference') }}</th>
            </template>
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
            </th>
            <td class="py-2 text-right"><MoneyText :cents="r.cents" class="font-medium" /></td>
            <template v-if="compared">
              <td class="hidden py-2 text-right text-muted-foreground sm:table-cell">
                <MoneyText v-if="r.averageCents !== null" :cents="r.averageCents" />
              </td>
              <td class="py-2 text-right">
                <DifferenceText
                  v-if="r.differenceCents !== null && r.tone"
                  :cents="r.differenceCents"
                  :tone="r.tone"
                />
              </td>
            </template>
          </tr>
        </tbody>
      </table>
    </CardContent>
  </Card>
</template>
