<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { DashboardResponse } from '@/shared/api/types'
import { formatBusinessDate } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import {
  Card,
  CardAction,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/shared/ui/card'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import CardEmpty from './CardEmpty.vue'

/** `undefined` while the dashboard loads. Upcoming occurrences do not depend on the month. */
const props = defineProps<{ data?: DashboardResponse }>()

/** The card shows the nearest few; the full list lives on the Recurring page. */
const SHOWN = 5
const shown = computed(() => props.data?.upcoming.slice(0, SHOWN) ?? [])
const hasMore = computed(() => (props.data?.upcoming.length ?? 0) > SHOWN)

const { t, locale } = useI18n()
</script>

<template>
  <Card>
    <CardHeader>
      <CardTitle>{{ t('dashboard.upcoming.title') }}</CardTitle>
      <CardDescription>{{ t('dashboard.upcoming.subtitle') }}</CardDescription>
      <CardAction v-if="hasMore">
        <RouterLink
          to="/recurring"
          :aria-label="t('dashboard.upcoming.viewAllLabel')"
          class="text-sm font-medium text-primary underline-offset-4 hover:underline"
        >
          {{ t('dashboard.upcoming.viewAll') }}
        </RouterLink>
      </CardAction>
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
          v-for="(u, i) in shown"
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
