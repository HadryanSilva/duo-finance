<script setup lang="ts">
import { computed, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAccount } from '@/shared/api/queries/account'
import { useBankAccounts } from '@/shared/api/queries/bank-accounts'
import { useCategories } from '@/shared/api/queries/categories'
import type { TransactionKind } from '@/shared/api/types'
import { Label } from '@/shared/ui/label'
import TextField from '@/shared/ui/TextField.vue'
import type { TransactionFilter } from '../api/transactions-api'
import NativeSelect from '@/shared/ui/NativeSelect.vue'

type Patch = Partial<Record<keyof TransactionFilter, string | undefined>>

/** The date range and the four selects; the search lives in TransactionFilters. */
const props = defineProps<{ filter: TransactionFilter }>()
const emit = defineEmits<{ change: [patch: Patch] }>()

const { t } = useI18n()
const ids = { kind: useId(), category: useId(), account: useId(), author: useId() }

const KINDS: readonly TransactionKind[] = ['EXPENSE', 'INCOME', 'TRANSFER']

const categoryKind = computed(() =>
  props.filter.kind === 'INCOME' || props.filter.kind === 'EXPENSE' ? props.filter.kind : undefined,
)
// Filtering by an archived category or account is still useful: list them, marked.
const categories = useCategories(categoryKind, true)
const bankAccounts = useBankAccounts(true)
const account = useAccount()
const members = computed(() => account.data.value?.members ?? [])

const optionName = (o: { name: string; archived: boolean }) =>
  o.archived ? t('transactions.archivedOption', { name: o.name }) : o.name

function onKind(value: string) {
  const kind = KINDS.find((k) => k === value)
  const patch: Patch = { kind }
  const current = categories.data.value?.find((c) => c.id === props.filter.categoryId)
  // a category filter only makes sense for its own kind
  if (props.filter.categoryId && (kind === 'TRANSFER' || (kind && current?.kind !== kind))) {
    patch.categoryId = undefined
  }
  emit('change', patch)
}
</script>

<template>
  <TextField
    type="date"
    :label="t('transactions.filters.from')"
    :model-value="filter.from ?? ''"
    :max="filter.to"
    @update:model-value="emit('change', { from: $event || undefined })"
  />
  <TextField
    type="date"
    :label="t('transactions.filters.to')"
    :model-value="filter.to ?? ''"
    :min="filter.from"
    @update:model-value="emit('change', { to: $event || undefined })"
  />
  <div class="grid gap-2">
    <Label :for="ids.kind">{{ t('transactions.filters.kind') }}</Label>
    <NativeSelect
      :id="ids.kind"
      :model-value="filter.kind ?? ''"
      @update:model-value="onKind($event ?? '')"
    >
      <option value="">{{ t('transactions.filters.allKinds') }}</option>
      <option v-for="k in KINDS" :key="k" :value="k">{{ t(`transactions.kind.${k}`) }}</option>
    </NativeSelect>
  </div>
  <div v-if="filter.kind !== 'TRANSFER'" class="grid gap-2">
    <Label :for="ids.category">{{ t('transactions.filters.category') }}</Label>
    <NativeSelect
      :id="ids.category"
      :model-value="filter.categoryId ?? ''"
      @update:model-value="emit('change', { categoryId: $event || undefined })"
    >
      <option value="">{{ t('transactions.filters.allCategories') }}</option>
      <option v-for="c in categories.data.value ?? []" :key="c.id" :value="c.id">
        {{ optionName(c) }}
      </option>
    </NativeSelect>
  </div>
  <div class="grid gap-2">
    <Label :for="ids.account">{{ t('transactions.filters.bankAccount') }}</Label>
    <NativeSelect
      :id="ids.account"
      :model-value="filter.bankAccountId ?? ''"
      @update:model-value="emit('change', { bankAccountId: $event || undefined })"
    >
      <option value="">{{ t('transactions.filters.allBankAccounts') }}</option>
      <option v-for="b in bankAccounts.data.value ?? []" :key="b.id" :value="b.id">
        {{ optionName(b) }}
      </option>
    </NativeSelect>
  </div>
  <div class="grid gap-2">
    <Label :for="ids.author">{{ t('transactions.filters.author') }}</Label>
    <NativeSelect
      :id="ids.author"
      :model-value="filter.createdBy ?? ''"
      @update:model-value="emit('change', { createdBy: $event || undefined })"
    >
      <option value="">{{ t('transactions.filters.allAuthors') }}</option>
      <option v-for="m in members" :key="m.userId" :value="m.userId">
        {{ m.displayName }}
      </option>
    </NativeSelect>
  </div>
</template>
