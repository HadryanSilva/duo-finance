<script setup lang="ts">
import { ArrowLeftRight } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useTransactions } from '@/shared/api/queries/transactions'
import { errorMessage } from '@/shared/i18n/error-codes'
import { swatchInkClass } from '@/shared/lib/contrast'
import { formatBusinessDate } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { Card, CardAction, CardContent, CardHeader, CardTitle } from '@/shared/ui/card'
import CategoryIcon from '@/shared/ui/CategoryIcon.vue'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import CardEmpty from './CardEmpty.vue'

const RECENT_COUNT = 5

const { t, locale } = useI18n()
// No date filter: the latest transactions regardless of the month on screen.
const recent = useTransactions({}, RECENT_COUNT)
const rows = computed(() => recent.data.value?.pages[0]?.content.slice(0, RECENT_COUNT) ?? [])
</script>

<template>
  <Card>
    <CardHeader>
      <CardTitle>{{ t('dashboard.recent.title') }}</CardTitle>
      <CardAction>
        <RouterLink
          to="/transactions"
          class="text-sm font-medium text-primary underline-offset-4 hover:underline"
        >
          {{ t('dashboard.recent.viewAll') }}
        </RouterLink>
      </CardAction>
    </CardHeader>
    <CardContent>
      <div v-if="recent.isPending.value" class="grid gap-3">
        <Skeleton class="h-9 w-full" />
        <Skeleton class="h-9 w-full" />
        <Skeleton class="h-9 w-full" />
      </div>
      <p v-else-if="recent.isError.value" role="alert" class="text-sm text-destructive">
        {{ errorMessage(recent.error.value) }}
      </p>
      <CardEmpty
        v-else-if="!rows.length"
        :title="t('dashboard.recent.emptyTitle')"
        :description="t('dashboard.recent.emptyDescription')"
        :cta="t('dashboard.recent.emptyCta')"
        to="/transactions?new=1"
      />
      <ul v-else class="grid gap-3" :aria-label="t('dashboard.recent.listLabel')">
        <li v-for="tx in rows" :key="tx.id" class="flex items-center gap-3">
          <span
            v-if="tx.category"
            class="flex size-9 shrink-0 items-center justify-center rounded-full"
            :class="swatchInkClass(tx.category.color)"
            :style="{ backgroundColor: tx.category.color }"
          >
            <!-- the color comes from the API as data, not from the design tokens -->
            <CategoryIcon :name="tx.category.icon" />
          </span>
          <span
            v-else
            class="flex size-9 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground"
          >
            <ArrowLeftRight class="size-4" aria-hidden="true" />
          </span>
          <span class="flex min-w-0 flex-1 flex-col">
            <span class="truncate text-sm font-medium text-foreground">{{
              tx.description || tx.category?.name || t(`transactions.kind.${tx.kind}`)
            }}</span>
            <span class="text-sm text-muted-foreground">{{
              formatBusinessDate(tx.date, locale as Locale)
            }}</span>
          </span>
          <MoneyText :cents="tx.amountCents" :kind="tx.kind" class="shrink-0 font-medium" />
        </li>
      </ul>
    </CardContent>
  </Card>
</template>
