<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { DashboardResponse } from '@/shared/api/types'
import { formatBusinessDate } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/shared/ui/card'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import CardEmpty from './CardEmpty.vue'

/** `undefined` while the dashboard loads. Upcoming occurrences do not depend on the month. */
defineProps<{ data?: DashboardResponse }>()

const { t, locale } = useI18n()
</script>

<template>
  <Card>
    <CardHeader>
      <CardTitle>{{ t('dashboard.upcoming.title') }}</CardTitle>
      <CardDescription>{{ t('dashboard.upcoming.subtitle') }}</CardDescription>
    </CardHeader>
    <CardContent>
      <div v-if="!data" class="grid gap-3">
        <Skeleton class="h-5 w-full" />
        <Skeleton class="h-5 w-full" />
        <Skeleton class="h-5 w-full" />
      </div>
      <CardEmpty
        v-else-if="!data.upcoming.length"
        :title="t('dashboard.upcoming.emptyTitle')"
        :description="t('dashboard.upcoming.emptyDescription')"
        :cta="t('dashboard.upcoming.emptyCta')"
        to="/recurring"
      />
      <ul v-else class="grid gap-3" :aria-label="t('dashboard.upcoming.listLabel')">
        <li
          v-for="(u, i) in data.upcoming"
          :key="`${u.ruleId}-${u.date}-${i}`"
          class="flex items-center justify-between gap-3 text-sm"
        >
          <span class="flex min-w-0 items-baseline gap-3">
            <span class="shrink-0 tabular-nums text-muted-foreground">{{
              formatBusinessDate(u.date, locale as Locale)
            }}</span>
            <span class="truncate text-foreground">{{
              u.description || t(`transactions.kind.${u.kind}`)
            }}</span>
          </span>
          <MoneyText :cents="u.amountCents" :kind="u.kind" class="shrink-0 font-medium" />
        </li>
      </ul>
    </CardContent>
  </Card>
</template>
