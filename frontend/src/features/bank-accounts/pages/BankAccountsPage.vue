<script setup lang="ts">
import { Plus } from '@lucide/vue'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAccount } from '@/shared/api/queries/account'
import {
  useArchiveBankAccount,
  useBankAccounts,
  useDeleteBankAccount,
  useUnarchiveBankAccount,
} from '@/shared/api/queries/bank-accounts'
import { isApiError } from '@/shared/api/problem'
import type { BankAccountResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import { Label } from '@/shared/ui/label'
import { Skeleton } from '@/shared/ui/skeleton'
import { Switch } from '@/shared/ui/switch'
import ConfirmDialog from '@/shared/ui/ConfirmDialog.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import ManageNotice from '@/shared/ui/ManageNotice.vue'
import MoneyText from '@/shared/ui/MoneyText.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import SidePanel from '@/shared/ui/SidePanel.vue'
import BankAccountForm from '../components/BankAccountForm.vue'
import BankAccountRow from '../components/BankAccountRow.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const showArchived = computed({
  get: () => route.query.archived === '1',
  set: (value: boolean) =>
    router.replace({ query: { ...route.query, archived: value ? '1' : undefined } }),
})

const accounts = useBankAccounts(showArchived)
const account = useAccount()
const archive = useArchiveBankAccount()
const unarchive = useUnarchiveBankAccount()
const remove = useDeleteBankAccount()

const holderNames = computed(
  () => new Map((account.data.value?.members ?? []).map((m) => [m.userId, m.displayName])),
)
const total = computed(() =>
  (accounts.data.value ?? [])
    .filter((a) => !a.archived)
    .reduce((sum, a) => sum + a.balanceCents, 0),
)

// Side panel: null closed, 'new' creating, otherwise the account being edited.
const panel = ref<'new' | BankAccountResponse | null>(null)
const panelOpen = computed({
  get: () => panel.value !== null,
  set: (open: boolean) => {
    if (!open) panel.value = null
  },
})

// /bank-accounts?new=1 (the dashboard's empty-state link) opens the create panel.
watch(
  () => route.query.new,
  (value) => {
    if (value !== '1') return
    panel.value = 'new'
    router.replace({ query: { ...route.query, new: undefined } })
  },
  { immediate: true },
)

const pendingDelete = ref<BankAccountResponse | null>(null)
const deleteOpen = computed({
  get: () => pendingDelete.value !== null,
  set: (open: boolean) => {
    if (!open) pendingDelete.value = null
  },
})

const notice = ref<{
  message: string
  archiveTarget: BankAccountResponse | null
  recurringLink: boolean
} | null>(null)

function fail(e: unknown, target: BankAccountResponse) {
  const code = isApiError(e) ? e.code : null
  notice.value = {
    message: errorMessage(e),
    archiveTarget: code === 'BANK_ACCOUNT_IN_USE' ? target : null,
    recurringLink: code === 'IN_USE_BY_ACTIVE_RULE',
  }
}

async function run(action: () => Promise<unknown>, target: BankAccountResponse) {
  notice.value = null
  try {
    await action()
  } catch (e) {
    fail(e, target)
  }
}

const onArchive = (a: BankAccountResponse) => run(() => archive.mutateAsync(a.id), a)
const onUnarchive = (a: BankAccountResponse) => run(() => unarchive.mutateAsync(a.id), a)
const onDelete = (a: BankAccountResponse | null) => {
  if (a) return run(() => remove.mutateAsync(a.id), a)
}
</script>

<template>
  <PageHeader :title="t('nav.bankAccounts')" :subtitle="t('bankAccounts.subtitle')">
    <template #actions>
      <Button @click="panel = 'new'">
        <Plus class="size-4" aria-hidden="true" />
        {{ t('bankAccounts.new') }}
      </Button>
    </template>
  </PageHeader>

  <ManageNotice
    v-if="notice"
    :message="notice.message"
    :can-archive="notice.archiveTarget !== null"
    :show-recurring-link="notice.recurringLink"
    @archive="notice.archiveTarget && onArchive(notice.archiveTarget)"
    @dismiss="notice = null"
  />

  <div class="mb-4 flex flex-wrap items-center justify-between gap-4">
    <p class="text-sm text-muted-foreground" data-testid="bank-total">
      {{ t('bankAccounts.total') }}:
      <MoneyText :cents="total" class="text-lg font-semibold text-foreground" />
    </p>
    <div class="flex items-center gap-2">
      <Switch id="show-archived-accounts" v-model="showArchived" />
      <Label for="show-archived-accounts">{{ t('common.showArchived') }}</Label>
    </div>
  </div>

  <div v-if="accounts.isPending.value" class="grid gap-3">
    <Skeleton class="h-16 w-full" />
    <Skeleton class="h-16 w-full" />
  </div>
  <p v-else-if="accounts.isError.value" role="alert" class="text-sm text-destructive">
    {{ errorMessage(accounts.error.value) }}
  </p>
  <EmptyState
    v-else-if="!accounts.data.value?.length"
    :title="t('bankAccounts.emptyTitle')"
    :description="t('bankAccounts.emptyDescription')"
  >
    <Button @click="panel = 'new'">{{ t('bankAccounts.new') }}</Button>
  </EmptyState>
  <ul v-else class="grid gap-3">
    <BankAccountRow
      v-for="a in accounts.data.value"
      :key="a.id"
      :account="a"
      :holder-name="
        a.holderUserId ? (holderNames.get(a.holderUserId) ?? t('bankAccounts.formerMember')) : null
      "
      @edit="panel = a"
      @archive="onArchive(a)"
      @unarchive="onUnarchive(a)"
      @remove="pendingDelete = a"
    />
  </ul>

  <SidePanel
    v-model:open="panelOpen"
    :title="
      panel === 'new' || panel === null
        ? t('bankAccounts.createTitle')
        : t('bankAccounts.editTitle')
    "
  >
    <BankAccountForm
      v-if="panel !== null"
      :key="panel === 'new' ? 'new' : panel.id"
      :account="panel === 'new' ? undefined : panel"
      @done="panel = null"
    />
  </SidePanel>

  <ConfirmDialog
    v-model:open="deleteOpen"
    :title="t('bankAccounts.deleteTitle', { name: pendingDelete?.name ?? '' })"
    :message="t('bankAccounts.deleteMessage')"
    :confirm-label="t('common.delete')"
    destructive
    @confirm="onDelete(pendingDelete)"
  />
</template>
