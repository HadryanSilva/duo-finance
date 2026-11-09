<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { errorMessage } from '@/shared/i18n/error-codes'
import { currentMonth } from '@/shared/lib/dates'
import MonthSelector from '@/shared/ui/MonthSelector.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import { useDashboard } from '../api/dashboard-api'
import BalanceCard from '../components/BalanceCard.vue'
import BudgetsCard from '../components/BudgetsCard.vue'
import IncomeExpenseChart from '../components/IncomeExpenseChart.vue'
import RecentTransactionsCard from '../components/RecentTransactionsCard.vue'
import SpendingDonut from '../components/SpendingDonut.vue'
import UpcomingCard from '../components/UpcomingCard.vue'

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
</script>

<template>
  <PageHeader :title="t('nav.dashboard')" :subtitle="t('dashboard.subtitle')" />

  <div class="mb-4">
    <MonthSelector v-model="month" />
  </div>

  <p v-if="dashboard.isError.value" role="alert" class="mb-4 text-sm text-destructive">
    {{ errorMessage(dashboard.error.value) }}
  </p>

  <div class="grid gap-4 md:grid-cols-2 lg:grid-cols-3">
    <BalanceCard :data="dashboard.data.value" />
    <IncomeExpenseChart class="lg:col-span-2" :data="dashboard.data.value" />
    <SpendingDonut :data="dashboard.data.value" />
    <BudgetsCard :data="dashboard.data.value" />
    <UpcomingCard :data="dashboard.data.value" />
    <RecentTransactionsCard class="lg:col-span-3" />
  </div>
</template>
