<script setup lang="ts">
import { computed, onBeforeUnmount, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAccount } from '@/shared/api/queries/account'
import { useBankAccounts } from '@/shared/api/queries/bank-accounts'
import { useCategories } from '@/shared/api/queries/categories'
import type { TransactionKind } from '@/shared/api/types'
import { debounce } from '@/shared/lib/debounce'
import { Label } from '@/shared/ui/label'
import TextField from '@/shared/ui/TextField.vue'
import {
  normalizeSearch,
  SEARCH_DEBOUNCE_MS,
  SEARCH_MAX,
  type TransactionFilter,
} from '../api/transactions-api'

type Patch = Partial<Record<keyof TransactionFilter, string | undefined>>

const props = defineProps<{ filter: TransactionFilter }>()
const emit = defineEmits<{ change: [patch: Patch] }>()

const { t } = useI18n()
const ids = { kind: useId(), category: useId(), account: useId(), author: useId() }

const KINDS: readonly TransactionKind[] = ['EXPENSE', 'INCOME', 'TRANSFER']
const SELECT =
  'h-9 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50'

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

const selectValue = (e: Event) => (e.target as HTMLSelectElement).value || undefined

// Search: typed text stays local, the trimmed value reaches the URL 300 ms after the last key.
const search = ref(props.filter.q ?? '')
// What this field last sent to the URL: its echo must not overwrite text typed since then.
let lastEmitted: string | null = null
const pushSearch = debounce((text: string) => {
  const q = normalizeSearch(text)
  if (q === (props.filter.q ?? '')) return
  lastEmitted = q
  emit('change', { q: q || undefined })
}, SEARCH_DEBOUNCE_MS)
onBeforeUnmount(() => pushSearch.cancel())

function onSearch(value: string | undefined) {
  search.value = (value ?? '').slice(0, SEARCH_MAX)
  pushSearch(search.value)
}

// The URL changed elsewhere (back button, shared link): show it unless it is what was typed.
watch(
  () => props.filter.q,
  (q) => {
    const incoming = q ?? ''
    if (incoming === lastEmitted) {
      lastEmitted = null // only the echo is skipped; a later change to the same value syncs
      return
    }
    if (normalizeSearch(search.value) !== incoming) search.value = incoming
  },
)
</script>

<template>
  <form
    role="search"
    :aria-label="t('transactions.filters.label')"
    class="mb-6 grid gap-3 rounded-lg border border-border bg-card p-4 sm:grid-cols-2 lg:grid-cols-4"
    @submit.prevent
  >
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
      <select
        :id="ids.kind"
        :value="filter.kind ?? ''"
        :class="SELECT"
        @change="onKind(($event.target as HTMLSelectElement).value)"
      >
        <option value="">{{ t('transactions.filters.allKinds') }}</option>
        <option v-for="k in KINDS" :key="k" :value="k">{{ t(`transactions.kind.${k}`) }}</option>
      </select>
    </div>
    <div v-if="filter.kind !== 'TRANSFER'" class="grid gap-2">
      <Label :for="ids.category">{{ t('transactions.filters.category') }}</Label>
      <select
        :id="ids.category"
        :value="filter.categoryId ?? ''"
        :class="SELECT"
        @change="emit('change', { categoryId: selectValue($event) })"
      >
        <option value="">{{ t('transactions.filters.allCategories') }}</option>
        <option v-for="c in categories.data.value ?? []" :key="c.id" :value="c.id">
          {{ optionName(c) }}
        </option>
      </select>
    </div>
    <div class="grid gap-2">
      <Label :for="ids.account">{{ t('transactions.filters.bankAccount') }}</Label>
      <select
        :id="ids.account"
        :value="filter.bankAccountId ?? ''"
        :class="SELECT"
        @change="emit('change', { bankAccountId: selectValue($event) })"
      >
        <option value="">{{ t('transactions.filters.allBankAccounts') }}</option>
        <option v-for="b in bankAccounts.data.value ?? []" :key="b.id" :value="b.id">
          {{ optionName(b) }}
        </option>
      </select>
    </div>
    <div class="grid gap-2">
      <Label :for="ids.author">{{ t('transactions.filters.author') }}</Label>
      <select
        :id="ids.author"
        :value="filter.createdBy ?? ''"
        :class="SELECT"
        @change="emit('change', { createdBy: selectValue($event) })"
      >
        <option value="">{{ t('transactions.filters.allAuthors') }}</option>
        <option v-for="m in members" :key="m.userId" :value="m.userId">
          {{ m.displayName }}
        </option>
      </select>
    </div>
    <div class="sm:col-span-2">
      <TextField
        type="search"
        :label="t('transactions.filters.search')"
        :placeholder="t('transactions.filters.searchPlaceholder')"
        :model-value="search"
        :maxlength="SEARCH_MAX"
        @update:model-value="onSearch"
      />
    </div>
  </form>
</template>
