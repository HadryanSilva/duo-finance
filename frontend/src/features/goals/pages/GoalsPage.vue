<script setup lang="ts">
import { Plus } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useCompleteGoal, useDeleteGoal, useGoals, useReopenGoal } from '@/shared/api/queries/goals'
import type { GoalResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { currentMonth } from '@/shared/lib/dates'
import { lastOpened } from '@/shared/lib/last-opened'
import type { Locale } from '@/shared/lib/money'
import { Button } from '@/shared/ui/button'
import ConfirmDialog from '@/shared/ui/ConfirmDialog.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import ManageNotice from '@/shared/ui/ManageNotice.vue'
import MoneyText from '@/shared/ui/MoneyText.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import SidePanel from '@/shared/ui/SidePanel.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import GoalAmountForm from '../components/GoalAmountForm.vue'
import GoalForm from '../components/GoalForm.vue'
import GoalMovements from '../components/GoalMovements.vue'
import GoalSuggestions from '../components/GoalSuggestions.vue'
import GoalRow from '../components/GoalRow.vue'
import SalaryCategoryPicker from '../components/SalaryCategoryPicker.vue'
import { moneyLabel } from '../lib/goal-copy'

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()

const goals = useGoals(currentMonth())
const complete = useCompleteGoal()
const reopen = useReopenGoal()
const remove = useDeleteGoal()
const active = computed(() => goals.data.value?.goals ?? [])
const completed = computed(() => goals.data.value?.completed ?? [])

// Side panels: the goal form (new or editing), the amount form and the history.
const form = ref<'new' | GoalResponse | null>(null)
const amount = ref<{ mode: 'deposit' | 'withdraw'; goal: GoalResponse } | null>(null)
const history = ref<GoalResponse | null>(null)

function panelModel<T>(source: { value: T | null }) {
  return computed({
    get: () => source.value !== null,
    set: (open: boolean) => {
      if (!open) source.value = null
    },
  })
}
// Titles and messages read the last opened value, so they hold while the panel animates out.
const formShown = lastOpened(form)
const amountShown = lastOpened(amount)

const formOpen = panelModel(form)
const amountOpen = panelModel(amount)
const historyOpen = panelModel(history)

// /goals?new=1 (the dashboard's link) opens the create panel.
watch(
  () => route.query.new,
  (value) => {
    if (value !== '1') return
    form.value = 'new'
    router.replace({ query: { ...route.query, new: undefined } })
  },
  { immediate: true },
)

const pendingComplete = ref<GoalResponse | null>(null)
const pendingDelete = ref<GoalResponse | null>(null)
const completeShown = lastOpened(pendingComplete)
const deleteShown = lastOpened(pendingDelete)
const completeOpen = panelModel(pendingComplete)
const deleteOpen = panelModel(pendingDelete)

const notice = ref<string | null>(null)
async function run(action: () => Promise<unknown>) {
  notice.value = null
  try {
    await action()
  } catch (e) {
    notice.value = errorMessage(e)
  }
}
const onComplete = () => {
  const g = pendingComplete.value
  if (g) void run(() => complete.mutateAsync(g.id))
}
const onDelete = () => {
  const g = pendingDelete.value
  if (g) void run(() => remove.mutateAsync(g.id))
}
const onReopen = (g: GoalResponse) => void run(() => reopen.mutateAsync(g.id))

const money = (cents: number) => moneyLabel(cents, locale.value as Locale)
// A completed goal's money was already released: deleting it frees nothing.
const deleteMessage = computed(() => {
  const g = deleteShown.value
  if (g?.status === 'COMPLETED') return t('goals.deleteCompletedMessage')
  return t('goals.deleteMessage', { amount: money(g?.savedCents ?? 0) })
})
</script>

<template>
  <PageHeader :title="t('nav.goals')" :subtitle="t('goals.subtitle')">
    <template #actions>
      <Button @click="form = 'new'">
        <Plus class="size-4" aria-hidden="true" />
        {{ t('goals.new') }}
      </Button>
    </template>
  </PageHeader>

  <ManageNotice v-if="notice" :message="notice" @dismiss="notice = null" />

  <SalaryCategoryPicker />

  <i18n-t
    v-if="active.length"
    keypath="goals.savedTotal"
    tag="p"
    :plural="active.length"
    class="mb-4 text-sm text-muted-foreground"
  >
    <template #amount>
      <MoneyText
        :cents="goals.data.value?.totalSavedCents ?? 0"
        class="font-medium text-foreground"
      />
    </template>
    <template #n>{{ active.length }}</template>
  </i18n-t>

  <GoalSuggestions v-if="goals.data.value" :overview="goals.data.value" class="mb-6" />

  <div v-if="goals.isPending.value" class="grid gap-3">
    <Skeleton class="h-28 w-full" />
    <Skeleton class="h-28 w-full" />
  </div>
  <p v-else-if="goals.isError.value" role="alert" class="text-sm text-destructive">
    {{ errorMessage(goals.error.value) }}
  </p>
  <template v-else>
    <EmptyState
      v-if="!active.length && !completed.length"
      :title="t('goals.emptyTitle')"
      :description="t('goals.emptyDescription')"
    >
      <Button @click="form = 'new'">{{ t('goals.new') }}</Button>
    </EmptyState>
    <ul v-else-if="active.length" class="grid gap-3" :aria-label="t('goals.listLabel')">
      <GoalRow
        v-for="g in active"
        :key="g.id"
        :goal="g"
        @deposit="amount = { mode: 'deposit', goal: g }"
        @withdraw="amount = { mode: 'withdraw', goal: g }"
        @history="history = g"
        @edit="form = g"
        @complete="pendingComplete = g"
        @remove="pendingDelete = g"
      />
    </ul>

    <section v-if="completed.length" class="mt-8 grid gap-3">
      <h2 class="text-lg font-semibold text-foreground">{{ t('goals.completedTitle') }}</h2>
      <ul class="grid gap-3" :aria-label="t('goals.completedTitle')">
        <GoalRow
          v-for="g in completed"
          :key="g.id"
          :goal="g"
          @reopen="onReopen(g)"
          @history="history = g"
          @remove="pendingDelete = g"
        />
      </ul>
    </section>
  </template>

  <SidePanel
    v-model:open="formOpen"
    :title="formShown === 'new' ? t('goals.createTitle') : t('goals.editTitle')"
  >
    <GoalForm
      v-if="form"
      :key="form === 'new' ? 'new' : form.id"
      :goal="form === 'new' ? undefined : form"
      @done="formOpen = false"
    />
  </SidePanel>

  <SidePanel
    v-model:open="amountOpen"
    :title="
      amountShown?.mode === 'withdraw' ? t('goals.actions.withdraw') : t('goals.actions.deposit')
    "
  >
    <GoalAmountForm
      v-if="amount"
      :key="`${amount.mode}-${amount.goal.id}`"
      :goal="amount.goal"
      :mode="amount.mode"
      @done="amountOpen = false"
    />
  </SidePanel>

  <SidePanel v-model:open="historyOpen" :title="t('goals.history.title')">
    <GoalMovements
      v-if="history"
      :key="history.id"
      :goal-id="history.id"
      :can-undo="history.status !== 'COMPLETED'"
    />
  </SidePanel>

  <ConfirmDialog
    v-model:open="completeOpen"
    :title="t('goals.completeTitle', { name: completeShown?.name ?? '' })"
    :message="t('goals.completeMessage', { amount: money(completeShown?.savedCents ?? 0) })"
    :confirm-label="t('goals.actions.complete')"
    @confirm="onComplete"
  />
  <ConfirmDialog
    v-model:open="deleteOpen"
    :title="t('goals.deleteTitle', { name: deleteShown?.name ?? '' })"
    :message="deleteMessage"
    :confirm-label="t('common.delete')"
    destructive
    @confirm="onDelete"
  />
</template>
