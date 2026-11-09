<script setup lang="ts">
import { Plus } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { RecurringRuleResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import ConfirmDialog from '@/shared/ui/ConfirmDialog.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import ManageNotice from '@/shared/ui/ManageNotice.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import SidePanel from '@/shared/ui/SidePanel.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import {
  useDeleteRecurringRule,
  useRecurringRules,
  useSetRecurringRuleActive,
} from '../api/recurring-api'
import RecurringRuleForm from '../components/RecurringRuleForm.vue'
import RecurringRuleRow from '../components/RecurringRuleRow.vue'

const { t } = useI18n()

const rules = useRecurringRules()
const setActive = useSetRecurringRuleActive()
const remove = useDeleteRecurringRule()

const all = computed(() => rules.data.value ?? [])
const activeRules = computed(() => all.value.filter((r) => r.active && !r.finished))
const inactiveRules = computed(() => all.value.filter((r) => !r.active || r.finished))

// Side panel: null closed, 'new' creating, otherwise the rule being edited.
const panel = ref<'new' | RecurringRuleResponse | null>(null)
const panelOpen = computed({
  get: () => panel.value !== null,
  set: (open: boolean) => {
    if (!open) panel.value = null
  },
})

const pendingDelete = ref<RecurringRuleResponse | null>(null)
const deleteOpen = computed({
  get: () => pendingDelete.value !== null,
  set: (open: boolean) => {
    if (!open) pendingDelete.value = null
  },
})

const notice = ref<string | null>(null)
async function run(action: () => Promise<unknown>) {
  notice.value = null
  try {
    await action()
  } catch (e) {
    // e.g. activating a rule whose category or account was archived meanwhile
    notice.value = errorMessage(e)
  }
}
const onToggle = (r: RecurringRuleResponse) =>
  run(() => setActive.mutateAsync({ id: r.id, active: !r.active }))
const onDelete = () => {
  const target = pendingDelete.value
  if (target) return run(() => remove.mutateAsync(target.id))
}
</script>

<template>
  <PageHeader :title="t('nav.recurring')" :subtitle="t('recurring.subtitle')">
    <template #actions>
      <Button @click="panel = 'new'">
        <Plus class="size-4" aria-hidden="true" />
        {{ t('recurring.new') }}
      </Button>
    </template>
  </PageHeader>

  <ManageNotice v-if="notice" :message="notice" @dismiss="notice = null" />

  <div v-if="rules.isPending.value" class="grid gap-3">
    <Skeleton class="h-16 w-full" />
    <Skeleton class="h-16 w-full" />
  </div>
  <p v-else-if="rules.isError.value" role="alert" class="text-sm text-destructive">
    {{ errorMessage(rules.error.value) }}
  </p>
  <EmptyState
    v-else-if="!all.length"
    :title="t('recurring.emptyTitle')"
    :description="t('recurring.emptyDescription')"
  >
    <Button @click="panel = 'new'">{{ t('recurring.new') }}</Button>
  </EmptyState>
  <div v-else class="grid gap-6">
    <section v-if="activeRules.length" aria-labelledby="recurring-active">
      <h2 id="recurring-active" class="mb-2 text-sm font-medium text-muted-foreground">
        {{ t('recurring.active') }}
      </h2>
      <ul class="grid gap-1">
        <RecurringRuleRow
          v-for="r in activeRules"
          :key="r.id"
          :rule="r"
          @edit="panel = r"
          @toggle="onToggle(r)"
          @remove="pendingDelete = r"
        />
      </ul>
    </section>
    <section v-if="inactiveRules.length" aria-labelledby="recurring-inactive">
      <h2 id="recurring-inactive" class="mb-2 text-sm font-medium text-muted-foreground">
        {{ t('recurring.inactive') }}
      </h2>
      <ul class="grid gap-1">
        <RecurringRuleRow
          v-for="r in inactiveRules"
          :key="r.id"
          :rule="r"
          @edit="panel = r"
          @toggle="onToggle(r)"
          @remove="pendingDelete = r"
        />
      </ul>
    </section>
  </div>

  <SidePanel
    v-model:open="panelOpen"
    :title="panel === 'new' ? t('recurring.createTitle') : t('recurring.editTitle')"
  >
    <RecurringRuleForm
      v-if="panel"
      :key="panel === 'new' ? 'new' : panel.id"
      :rule="panel === 'new' ? undefined : panel"
      @done="panelOpen = false"
    />
  </SidePanel>

  <ConfirmDialog
    v-model:open="deleteOpen"
    :title="t('recurring.deleteTitle')"
    :message="t('recurring.deleteMessage')"
    :confirm-label="t('common.delete')"
    destructive
    @confirm="onDelete"
  />
</template>
