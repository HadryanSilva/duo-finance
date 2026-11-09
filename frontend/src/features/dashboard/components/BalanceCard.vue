<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { DashboardResponse } from '@/shared/api/types'
import { formatBusinessDate } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/card'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import CardEmpty from './CardEmpty.vue'

/** `undefined` while the dashboard loads. */
const props = defineProps<{ data?: DashboardResponse }>()

const { t, locale } = useI18n()
const asOf = computed(() =>
  props.data ? formatBusinessDate(props.data.balancesAsOf, locale.value as Locale) : '',
)
</script>

<template>
  <Card>
    <CardHeader>
      <CardTitle>{{ t('dashboard.balance.title') }}</CardTitle>
    </CardHeader>
    <CardContent>
      <div v-if="!data" class="grid gap-3">
        <Skeleton class="h-9 w-40" />
        <Skeleton class="h-5 w-full" />
        <Skeleton class="h-5 w-full" />
      </div>
      <CardEmpty
        v-else-if="!data.accounts.length"
        :title="t('dashboard.balance.emptyTitle')"
        :description="t('dashboard.balance.emptyDescription')"
        :cta="t('dashboard.balance.emptyCta')"
        to="/bank-accounts?new=1"
      />
      <template v-else>
        <p class="text-sm text-muted-foreground">{{ t('dashboard.balance.total') }}</p>
        <p class="text-3xl font-semibold text-foreground">
          <MoneyText :cents="data.totalBalanceCents" />
        </p>
        <p class="text-sm text-muted-foreground">
          {{ t('dashboard.balance.asOf', { date: asOf }) }}
        </p>
        <ul class="mt-4 grid gap-2" :aria-label="t('dashboard.balance.accountsLabel')">
          <li
            v-for="a in data.accounts"
            :key="a.id"
            class="flex items-center justify-between gap-3 text-sm"
          >
            <span class="min-w-0 truncate text-foreground">{{ a.name }}</span>
            <MoneyText :cents="a.balanceCents" class="shrink-0 font-medium" />
          </li>
        </ul>
      </template>
    </CardContent>
  </Card>
</template>
