<script setup lang="ts">
import { Pencil, Plus, Trash } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import type { BudgetResponse } from '@/shared/api/types'
import { currentMonth } from '@/shared/lib/dates'
import { errorMessage } from '@/shared/i18n/error-codes'
import { swatchInkClass } from '@/shared/lib/contrast'
import ArchivedBadge from '@/shared/ui/ArchivedBadge.vue'
import BudgetProgressBar from '@/shared/ui/BudgetProgressBar.vue'
import { Button } from '@/shared/ui/button'
import CategoryIcon from '@/shared/ui/CategoryIcon.vue'
import ConfirmDialog from '@/shared/ui/ConfirmDialog.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import ManageNotice from '@/shared/ui/ManageNotice.vue'
import MonthSelector from '@/shared/ui/MonthSelector.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import SidePanel from '@/shared/ui/SidePanel.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import { useBudgets, useDeleteBudget } from '../api/budgets-api'
import BudgetForm from '../components/BudgetForm.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const MONTH = /^\d{4}-(0[1-9]|1[0-2])$/
/** The URL holds the month, so a shared link shows the same budgets. */
const month = computed({
  get: () => {
    const q = route.query.month
    return typeof q === 'string' && MONTH.test(q) ? q : currentMonth()
  },
  set: (value: string) => void router.replace({ query: { ...route.query, month: value } }),
})

const budgets = useBudgets(month)
const remove = useDeleteBudget()
const rows = computed(() => budgets.data.value ?? [])
const budgetedIds = computed(() => rows.value.map((b) => b.category.id))

// Side panel: null closed, 'new' creating, otherwise the budget being edited.
const panel = ref<'new' | BudgetResponse | null>(null)
const panelOpen = computed({
  get: () => panel.value !== null,
  set: (open: boolean) => {
    if (!open) panel.value = null
  },
})

const pendingDelete = ref<BudgetResponse | null>(null)
const deleteOpen = computed({
  get: () => pendingDelete.value !== null,
  set: (open: boolean) => {
    if (!open) pendingDelete.value = null
  },
})
const notice = ref<string | null>(null)
async function onDelete() {
  const target = pendingDelete.value
  if (!target) return
  notice.value = null
  try {
    await remove.mutateAsync(target.id)
  } catch (e) {
    notice.value = errorMessage(e)
  }
}
</script>

<template>
  <PageHeader :title="t('nav.budgets')" :subtitle="t('budgets.subtitle')">
    <template #actions>
      <Button @click="panel = 'new'">
        <Plus class="size-4" aria-hidden="true" />
        {{ t('budgets.new') }}
      </Button>
    </template>
  </PageHeader>

  <ManageNotice v-if="notice" :message="notice" @dismiss="notice = null" />

  <div class="mb-4">
    <MonthSelector v-model="month" />
  </div>

  <div v-if="budgets.isPending.value" class="grid gap-3">
    <Skeleton class="h-20 w-full" />
    <Skeleton class="h-20 w-full" />
  </div>
  <p v-else-if="budgets.isError.value" role="alert" class="text-sm text-destructive">
    {{ errorMessage(budgets.error.value) }}
  </p>
  <EmptyState
    v-else-if="!rows.length"
    :title="t('budgets.emptyTitle')"
    :description="t('budgets.emptyDescription')"
  >
    <Button @click="panel = 'new'">{{ t('budgets.new') }}</Button>
  </EmptyState>
  <ul v-else class="grid gap-3" :aria-label="t('budgets.listLabel')">
    <li
      v-for="b in rows"
      :key="b.id"
      class="grid gap-3 rounded-lg border border-border bg-card p-4"
    >
      <div class="flex items-center gap-3">
        <span
          class="flex size-9 shrink-0 items-center justify-center rounded-full"
          :class="swatchInkClass(b.category.color)"
          :style="{ backgroundColor: b.category.color }"
        >
          <!-- the color comes from the API as data, not from the design tokens -->
          <CategoryIcon :name="b.category.icon" />
        </span>
        <span class="flex min-w-0 flex-1 items-center gap-2">
          <span class="truncate font-medium text-foreground">{{ b.category.name }}</span>
          <ArchivedBadge v-if="b.category.archived" />
        </span>
        <div class="flex items-center">
          <Button
            variant="ghost"
            size="icon"
            :aria-label="t('budgets.edit', { name: b.category.name })"
            @click="panel = b"
          >
            <Pencil class="size-4" aria-hidden="true" />
          </Button>
          <Button
            variant="ghost"
            size="icon"
            :aria-label="t('budgets.remove', { name: b.category.name })"
            @click="pendingDelete = b"
          >
            <Trash class="size-4" aria-hidden="true" />
          </Button>
        </div>
      </div>
      <BudgetProgressBar
        :spent-cents="b.spentCents"
        :limit-cents="b.monthlyLimitCents"
        :percent="b.percent"
        :status="b.status"
        :label="b.category.name"
      />
    </li>
  </ul>

  <SidePanel
    v-model:open="panelOpen"
    :title="panel === 'new' ? t('budgets.createTitle') : t('budgets.editTitle')"
  >
    <BudgetForm
      v-if="panel"
      :key="panel === 'new' ? 'new' : panel.id"
      :budget="panel === 'new' ? undefined : panel"
      :budgeted-category-ids="budgetedIds"
      @done="panelOpen = false"
    />
  </SidePanel>

  <ConfirmDialog
    v-model:open="deleteOpen"
    :title="t('budgets.deleteTitle')"
    :message="t('budgets.deleteMessage', { name: pendingDelete?.category.name ?? '' })"
    :confirm-label="t('common.delete')"
    destructive
    @confirm="onDelete"
  />
</template>
