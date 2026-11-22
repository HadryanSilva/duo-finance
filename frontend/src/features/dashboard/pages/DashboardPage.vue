<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { errorMessage } from '@/shared/i18n/error-codes'
import { currentMonth } from '@/shared/lib/dates'
import { useToday } from '@/shared/lib/use-today'
import MonthSelector from '@/shared/ui/MonthSelector.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import { useDashboard } from '../api/dashboard-api'
import BalanceCard from '../components/BalanceCard.vue'
import BudgetsCard from '../components/BudgetsCard.vue'
import GoalsCard from '../components/GoalsCard.vue'
import MonthSummaryCard from '../components/MonthSummaryCard.vue'
import RecentTransactionsCard from '../components/RecentTransactionsCard.vue'
import SpendingDonut from '../components/SpendingDonut.vue'
import StaleImportsNotice from '../components/StaleImportsNotice.vue'
import UpcomingCard from '../components/UpcomingCard.vue'
import { staleImports } from '../lib/figures'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/
/** The URL holds the month, so a shared link shows the same dashboard. */
const month = computed({
  get: () => {
    const q = route.query.month
    return typeof q === 'string' && MONTH.test(q) ? q : currentMonth()
  },
  set: (value: string) => void router.replace({ query: { ...route.query, month: value } }),
})

const dashboard = useDashboard(month)
/** Measured against the browser's today, whatever month is shown: it is about the data, not the month. */
const today = useToday()
const stale = computed(() => staleImports(dashboard.data.value?.accounts ?? [], today.value))
</script>

<template>
  <PageHeader :title="t('nav.dashboard')" :subtitle="t('dashboard.subtitle')" />

  <div class="mb-4">
    <MonthSelector v-model="month" />
  </div>

  <p v-if="dashboard.isError.value" role="alert" class="mb-4 text-sm text-destructive">
    {{ errorMessage(dashboard.error.value) }}
  </p>

  <!-- Reading order: how the month went, where the money went, then the supporting figures. -->
  <div class="grid gap-4">
    <!-- A failed dashboard request hides its cards instead of leaving them on skeletons. -->
    <template v-if="!dashboard.isError.value">
      <StaleImportsNotice :items="stale" />
      <MonthSummaryCard :data="dashboard.data.value" />
      <div class="grid gap-4 lg:grid-cols-5">
        <SpendingDonut class="lg:col-span-3" :data="dashboard.data.value" :month="month" />
        <BudgetsCard class="lg:col-span-2" :data="dashboard.data.value" :month="month" />
      </div>
      <div class="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
        <BalanceCard :data="dashboard.data.value" />
        <UpcomingCard :data="dashboard.data.value" />
        <GoalsCard
          class="md:col-span-2 lg:col-span-1"
          :data="dashboard.data.value"
          :month="month"
        />
      </div>
    </template>
    <RecentTransactionsCard />
  </div>
</template>
