<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DashboardResponse } from '@/shared/api/types'
import { Card, CardContent } from '@/shared/ui/card'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import { monthSummary } from '../lib/figures'

/** `undefined` while the dashboard loads. */
const props = defineProps<{ data?: DashboardResponse }>()

const { t } = useI18n()
const summary = computed(() =>
  props.data ? monthSummary(props.data.incomeCents, props.data.expenseCents) : null,
)
</script>

<template>
  <Card>
    <CardContent>
      <div v-if="!data || !summary" class="grid gap-4 sm:grid-cols-3">
        <Skeleton class="h-14 w-full" />
        <Skeleton class="h-14 w-full" />
        <Skeleton class="h-14 w-full" />
      </div>
      <dl v-else class="grid gap-4 sm:grid-cols-3" :aria-label="t('dashboard.summary.label')">
        <div>
          <dt class="text-sm text-muted-foreground">{{ t('dashboard.summary.in') }}</dt>
          <dd class="text-2xl font-semibold">
            <MoneyText :cents="data.incomeCents" kind="INCOME" />
          </dd>
        </div>
        <div>
          <dt class="text-sm text-muted-foreground">{{ t('dashboard.summary.out') }}</dt>
          <dd class="text-2xl font-semibold">
            <MoneyText :cents="data.expenseCents" kind="EXPENSE" />
          </dd>
        </div>
        <div>
          <dt class="text-sm text-muted-foreground">
            {{
              summary.kind === 'short' ? t('dashboard.summary.short') : t('dashboard.summary.left')
            }}
          </dt>
          <dd
            class="text-2xl font-semibold"
            :class="summary.kind === 'short' ? 'text-expense' : 'text-foreground'"
          >
            <MoneyText :cents="summary.amountCents" />
          </dd>
          <dd v-if="summary.sharePercent !== null" class="text-sm text-muted-foreground">
            {{ t('dashboard.summary.leftShare', { percent: summary.sharePercent }) }}
          </dd>
        </div>
      </dl>
    </CardContent>
  </Card>
</template>
