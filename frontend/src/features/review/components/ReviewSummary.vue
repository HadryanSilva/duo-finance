<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { MonthReviewResponse } from '@/shared/api/types'
import { Card, CardContent } from '@/shared/ui/card'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { differenceTone } from '../lib/review'
import DifferenceText from './DifferenceText.vue'

const props = defineProps<{ data: MonthReviewResponse }>()

const { t } = useI18n()

const figures = computed(() => {
  const { income, expense, left } = props.data
  const row = (
    key: string,
    label: string,
    f: { cents: number; averageCents: number | null },
    moreIsGood: boolean,
    kind?: 'INCOME' | 'EXPENSE',
  ) => ({
    key,
    label,
    kind,
    cents: f.cents,
    averageCents: f.averageCents,
    difference: f.averageCents === null ? null : f.cents - f.averageCents,
    tone: differenceTone(f.cents, f.averageCents, moreIsGood),
  })
  return [
    row('in', t('dashboard.summary.in'), income, true, 'INCOME'),
    row('out', t('dashboard.summary.out'), expense, false, 'EXPENSE'),
    row(
      'left',
      left.cents < 0 ? t('dashboard.summary.short') : t('dashboard.summary.left'),
      left,
      true,
    ),
  ]
})
</script>

<template>
  <Card>
    <CardContent>
      <dl class="grid gap-4 sm:grid-cols-3" :aria-label="t('review.summaryLabel')">
        <div v-for="f in figures" :key="f.key">
          <dt class="text-sm text-muted-foreground">{{ f.label }}</dt>
          <dd class="text-2xl font-semibold">
            <MoneyText :cents="f.cents" :kind="f.kind" />
          </dd>
          <dd v-if="f.averageCents !== null" class="flex flex-wrap gap-x-2 text-sm">
            <span class="text-muted-foreground">
              {{ t('review.average', { n: data.comparedMonths }, data.comparedMonths) }}:
              <MoneyText :cents="f.averageCents" />
            </span>
            <DifferenceText
              v-if="f.difference !== null && f.tone"
              :cents="f.difference"
              :tone="f.tone"
            />
          </dd>
        </div>
      </dl>
    </CardContent>
  </Card>
</template>
