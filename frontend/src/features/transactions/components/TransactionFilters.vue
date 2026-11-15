<script setup lang="ts">
import { SlidersHorizontal } from '@lucide/vue'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useIsDesktop } from '@/shared/lib/breakpoints'
import { debounce } from '@/shared/lib/debounce'
import { Button } from '@/shared/ui/button'
import SidePanel from '@/shared/ui/SidePanel.vue'
import TextField from '@/shared/ui/TextField.vue'
import {
  activeFilterCount,
  normalizeSearch,
  SEARCH_DEBOUNCE_MS,
  SEARCH_MAX,
  SHEET_FILTER_KEYS,
  type TransactionFilter,
} from '../api/transactions-api'
import TransactionFilterFields from './TransactionFilterFields.vue'

type Patch = Partial<Record<keyof TransactionFilter, string | undefined>>

const props = defineProps<{ filter: TransactionFilter }>()
const emit = defineEmits<{ change: [patch: Patch] }>()

const { t } = useI18n()

// Below md the search stays on the page and the other filters move into a bottom sheet.
const isDesktop = useIsDesktop()
const sheetOpen = ref(false)
// Rotating to landscape (wider than md) unmounts the sheet: it must not reopen on the way back.
watch(isDesktop, (desktop) => {
  if (desktop) sheetOpen.value = false
})
const count = computed(() => activeFilterCount(props.filter))
const buttonLabel = computed(() =>
  count.value
    ? t('transactions.filters.openWithCount', { n: count.value }, count.value)
    : t('transactions.filters.open'),
)

function clearSheetFilters() {
  // the search is not a sheet filter: it stays as typed
  emit('change', Object.fromEntries(SHEET_FILTER_KEYS.map((key) => [key, undefined])))
}

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
    :class="
      isDesktop
        ? 'mb-6 grid gap-3 rounded-lg border border-border bg-card p-4 sm:grid-cols-2 lg:grid-cols-4'
        : 'mb-6 flex items-end gap-2'
    "
    @submit.prevent
  >
    <TransactionFilterFields v-if="isDesktop" :filter="filter" @change="emit('change', $event)" />
    <div :class="isDesktop ? 'sm:col-span-2' : 'min-w-0 flex-1'">
      <TextField
        type="search"
        :label="t('transactions.filters.search')"
        :placeholder="t('transactions.filters.searchPlaceholder')"
        :model-value="search"
        :maxlength="SEARCH_MAX"
        @update:model-value="onSearch"
      />
    </div>
    <Button
      v-if="!isDesktop"
      type="button"
      variant="outline"
      :aria-label="buttonLabel"
      @click="sheetOpen = true"
    >
      <SlidersHorizontal class="size-4" aria-hidden="true" />
      {{ t('transactions.filters.open') }}
      <span v-if="count">· {{ count }}</span>
    </Button>
  </form>

  <SidePanel v-if="!isDesktop" v-model:open="sheetOpen" :title="t('transactions.filters.label')">
    <div class="grid gap-3">
      <TransactionFilterFields :filter="filter" @change="emit('change', $event)" />
      <Button v-if="count" type="button" variant="ghost" @click="clearSheetFilters">
        {{ t('transactions.filters.clear') }}
      </Button>
    </div>
  </SidePanel>
</template>
