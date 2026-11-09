<script setup lang="ts">
import { Plus } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter, type LocationQueryRaw } from 'vue-router'
import { useBankAccounts } from '@/shared/api/queries/bank-accounts'
import { useCategories } from '@/shared/api/queries/categories'
import { useTransaction, useTransactions } from '@/shared/api/queries/transactions'
import { isApiError } from '@/shared/api/problem'
import type { TransactionResponse } from '@/shared/api/types'
import { groupByDate } from '@/shared/lib/dates'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import { Skeleton } from '@/shared/ui/skeleton'
import EmptyState from '@/shared/ui/EmptyState.vue'
import ManageNotice from '@/shared/ui/ManageNotice.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import SidePanel from '@/shared/ui/SidePanel.vue'
import { filterFromQuery, queryPatch, type TransactionFilter } from '../api/transactions-api'
import TransactionDayGroup from '../components/TransactionDayGroup.vue'
import TransactionFilters from '../components/TransactionFilters.vue'
import TransactionForm from '../components/TransactionForm.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

/** The URL is the source of truth: a shared link reopens the same list. */
const filter = computed(() => filterFromQuery(route.query))

function replaceQuery(patch: LocationQueryRaw) {
  return router.replace({ query: { ...route.query, ...patch } })
}

// A new filter is a new query key, so paging starts over from page 0.
const onFilterChange = (patch: Parameters<typeof queryPatch>[0]) => replaceQuery(queryPatch(patch))

const transactions = useTransactions(filter)
const rows = computed(() => transactions.data.value?.pages.flatMap((p) => p.content) ?? [])
const days = computed(() => groupByDate(rows.value))

// A category or bank account filter that was deleted or belongs to another Account answers 404.
const allCategories = useCategories(undefined, true)
const allBankAccounts = useBankAccounts(true)
const notice = ref<string | null>(null)
watch(
  () => transactions.error.value,
  (e) => {
    if (!isApiError(e) || e.code !== 'NOT_FOUND') return
    const { categoryId, bankAccountId } = filter.value
    if (!categoryId && !bankAccountId) return
    const known = (list: { id: string }[] | undefined, id: string | undefined) =>
      id !== undefined && list !== undefined && list.some((x) => x.id === id)
    const keepCategory = known(allCategories.data.value, categoryId)
    const keepAccount = known(allBankAccounts.data.value, bankAccountId)
    // Unable to tell which one failed: drop both rather than keep failing.
    const both = keepCategory === keepAccount
    const patch: Partial<Record<keyof TransactionFilter, undefined>> = {}
    if (categoryId && (both || !keepCategory)) patch.categoryId = undefined
    if (bankAccountId && (both || !keepAccount)) patch.bankAccountId = undefined
    notice.value = t('transactions.filterRemoved')
    void replaceQuery(patch)
  },
)
const listError = computed(() => {
  const e = transactions.error.value
  return e && !(isApiError(e) && e.code === 'NOT_FOUND') ? errorMessage(e) : null
})

// Side panel: ?new=1 creates, ?edit=<id> edits; closing removes the param.
const editId = computed(() => (typeof route.query.edit === 'string' ? route.query.edit : null))
const isNew = computed(() => route.query.new === '1' && editId.value === null)
const editing = useTransaction(editId)
const panelOpen = computed({
  get: () => isNew.value || editId.value !== null,
  set: (open: boolean) => {
    if (!open) void replaceQuery({ new: undefined, edit: undefined })
  },
})
const openNew = () => replaceQuery({ new: '1', edit: undefined })
const openEdit = (tx: TransactionResponse) => replaceQuery({ edit: tx.id, new: undefined })
const closePanel = () => (panelOpen.value = false)
</script>

<template>
  <PageHeader :title="t('nav.transactions')" :subtitle="t('transactions.subtitle')">
    <template #actions>
      <Button @click="openNew">
        <Plus class="size-4" aria-hidden="true" />
        {{ t('transactions.new') }}
      </Button>
    </template>
  </PageHeader>

  <ManageNotice v-if="notice" :message="notice" @dismiss="notice = null" />

  <TransactionFilters :filter="filter" @change="onFilterChange" />

  <div v-if="transactions.isPending.value" class="grid gap-3">
    <Skeleton class="h-16 w-full" />
    <Skeleton class="h-16 w-full" />
  </div>
  <p v-else-if="listError" role="alert" class="text-sm text-destructive">{{ listError }}</p>
  <EmptyState
    v-else-if="!rows.length"
    :title="t('transactions.emptyTitle')"
    :description="t('transactions.emptyDescription')"
  >
    <Button @click="openNew">{{ t('transactions.new') }}</Button>
  </EmptyState>
  <template v-else>
    <ul class="grid gap-4" :aria-label="t('transactions.listLabel')">
      <TransactionDayGroup
        v-for="day in days"
        :key="day.date"
        :date="day.date"
        :rows="day.rows"
        @edit="openEdit"
      />
    </ul>
    <div v-if="transactions.hasNextPage.value" class="mt-6 flex justify-center">
      <Button
        variant="outline"
        :disabled="transactions.isFetchingNextPage.value"
        :aria-busy="transactions.isFetchingNextPage.value ? 'true' : undefined"
        @click="transactions.fetchNextPage()"
      >
        {{
          transactions.isFetchingNextPage.value
            ? t('transactions.loadingMore')
            : t('transactions.loadMore')
        }}
      </Button>
    </div>
    <p class="sr-only" role="status" aria-live="polite">
      {{ transactions.isFetchingNextPage.value ? t('transactions.loadingMore') : '' }}
    </p>
  </template>

  <SidePanel
    v-model:open="panelOpen"
    :title="editId ? t('transactions.editTitle') : t('transactions.createTitle')"
  >
    <template v-if="editId">
      <div v-if="editing.isPending.value" class="grid gap-3">
        <Skeleton class="h-9 w-full" />
        <Skeleton class="h-9 w-full" />
      </div>
      <p v-else-if="editing.isError.value" role="alert" class="text-sm text-destructive">
        {{ errorMessage(editing.error.value) }}
      </p>
      <TransactionForm
        v-else-if="editing.data.value"
        :key="editing.data.value.id"
        :transaction="editing.data.value"
        @done="closePanel"
      />
    </template>
    <TransactionForm v-else-if="isNew" key="new" @done="closePanel" />
  </SidePanel>
</template>
