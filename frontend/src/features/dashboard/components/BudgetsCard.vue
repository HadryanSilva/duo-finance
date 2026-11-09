<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { DashboardResponse } from '@/shared/api/types'
import BudgetProgressBar from '@/shared/ui/BudgetProgressBar.vue'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/card'
import { Skeleton } from '@/shared/ui/skeleton'
import CardEmpty from './CardEmpty.vue'

/** `undefined` while the dashboard loads. */
defineProps<{ data?: DashboardResponse }>()

const { t } = useI18n()
</script>

<template>
  <Card>
    <CardHeader>
      <CardTitle>{{ t('dashboard.budgets.title') }}</CardTitle>
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
          <span class="truncate text-sm font-medium text-foreground">{{ b.category.name }}</span>
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
