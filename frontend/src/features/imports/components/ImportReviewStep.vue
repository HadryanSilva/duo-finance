<script setup lang="ts">
import { computed, onMounted, ref, useTemplateRef, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCategories } from '@/shared/api/queries/categories'
import type { PreviewResponse } from '@/shared/api/types'
import { Button } from '@/shared/ui/button'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/ui/tabs'
import {
  canSubmit,
  sameGroup,
  setCategory,
  setInclude,
  totals,
  type ReviewRow,
} from '../lib/review-state'
import ImportReviewRow from './ImportReviewRow.vue'
import ImportSkippedList from './ImportSkippedList.vue'

/** Step 2: the planned rows by tab, with include and category per row, and the submit footer. */
const props = defineProps<{
  skipped: PreviewResponse['skipped']['rows']
  accountName: (id: string | null) => string
  /** A preview or the commit is running. */
  pending: boolean
  error: string | null
  /** Commit errors by rowId (codes), cleared row by row as the user edits. */
  rowErrors: Record<string, string>
}>()
const rows = defineModel<ReviewRow[]>('rows', { required: true })
const emit = defineEmits<{ submit: []; clearRowError: [rowId: string] }>()

const { t } = useI18n()
const tab = ref<'new' | 'duplicates' | 'skipped'>('new')

// Each preview remounts this step, so focus lands on its heading whenever a review appears.
const heading = useTemplateRef<HTMLElement>('heading')
onMounted(() => heading.value?.focus())

const expense = useCategories('EXPENSE')
const income = useCategories('INCOME')
const options = (list: { id: string; name: string }[] | undefined) =>
  (list ?? []).map((c) => ({ id: c.id, label: c.name }))
const expenseOptions = computed(() => options(expense.data.value))
const incomeOptions = computed(() => options(income.data.value))
const categoriesFor = (row: ReviewRow) =>
  row.kind === 'INCOME' ? incomeOptions.value : expenseOptions.value

// ALREADY_IMPORTED rows stay in "New", locked, so the file's rows are all accounted for.
const newRows = computed(() => rows.value.filter((r) => r.status !== 'POSSIBLE_DUPLICATE'))
const duplicateRows = computed(() => rows.value.filter((r) => r.status === 'POSSIBLE_DUPLICATE'))
const lists = computed(() => [
  { key: 'new', rows: newRows.value },
  { key: 'duplicates', rows: duplicateRows.value },
])
const sums = computed(() => totals(rows.value))
const ready = computed(() => canSubmit(rows.value))

/** Row whose category just changed, offering to apply it to the same description. */
const prompt = ref<{ rowId: string; categoryId: string; count: number } | null>(null)

function clearError(rowId: string) {
  if (rowId in props.rowErrors) emit('clearRowError', rowId)
}

function onCategory(rowId: string, categoryId: string) {
  clearError(rowId)
  rows.value = setCategory(rows.value, rowId, categoryId, false)
  const others = new Set(sameGroup(rows.value, rowId))
  const count = rows.value.filter(
    (r) => others.has(r.rowId) && r.status !== 'ALREADY_IMPORTED',
  ).length
  prompt.value = count ? { rowId, categoryId, count } : null
}

function applyToGroup() {
  const p = prompt.value
  if (!p) return
  rows.value = setCategory(rows.value, p.rowId, p.categoryId, true)
  sameGroup(rows.value, p.rowId).forEach(clearError)
  prompt.value = null
}

const onInclude = (rowId: string, include: boolean) => {
  clearError(rowId)
  rows.value = setInclude(rows.value, rowId, include)
}

/** Codes with their own message; any other (kind mismatch, not found, ...) gets the generic one. */
const ROW_ERROR_MESSAGES = new Set([
  'CATEGORY_ARCHIVED',
  'CATEGORY_REQUIRED',
  'ROW_ALREADY_IMPORTED',
])

function rowError(row: ReviewRow): { message: string; onInclude: boolean } | undefined {
  const code = props.rowErrors[row.rowId]
  if (!code) return undefined
  const key = ROW_ERROR_MESSAGES.has(code) ? code : 'invalid'
  return {
    message: t(`imports.review.rowErrors.${key}`),
    onInclude: code === 'ROW_ALREADY_IMPORTED',
  }
}

// New commit errors: show the tab holding the first row that needs fixing.
watch(
  () => props.rowErrors,
  (errors) => {
    const first = rows.value.find((r) => r.rowId in errors)
    if (first) tab.value = first.status === 'POSSIBLE_DUPLICATE' ? 'duplicates' : 'new'
  },
)

const tabLabel = (key: 'new' | 'duplicates' | 'skipped', n: number) =>
  t('imports.review.tabCount', { label: t(`imports.review.tabs.${key}`), n })
</script>

<template>
  <section class="mt-6 grid gap-4">
    <h2 ref="heading" tabindex="-1" class="text-lg font-semibold outline-none">
      {{ t('imports.review.title') }}
    </h2>
    <p v-if="error" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ error }}
    </p>

    <Tabs v-model="tab">
      <TabsList>
        <TabsTrigger value="new">{{ tabLabel('new', newRows.length) }}</TabsTrigger>
        <TabsTrigger value="duplicates">
          {{ tabLabel('duplicates', duplicateRows.length) }}
        </TabsTrigger>
        <TabsTrigger value="skipped">{{ tabLabel('skipped', skipped.length) }}</TabsTrigger>
      </TabsList>

      <TabsContent v-for="list in lists" :key="list.key" :value="list.key">
        <ul v-if="list.rows.length" class="grid gap-2">
          <ImportReviewRow
            v-for="row in list.rows"
            :key="row.rowId"
            :row="row"
            :categories="categoriesFor(row)"
            :account-name="accountName"
            :apply-count="prompt?.rowId === row.rowId ? prompt.count : undefined"
            :error="rowError(row)"
            @include="onInclude(row.rowId, $event)"
            @category="onCategory(row.rowId, $event)"
            @apply="applyToGroup"
            @dismiss="prompt = null"
          />
        </ul>
        <p v-else class="text-sm text-muted-foreground">{{ t('imports.review.empty') }}</p>
      </TabsContent>

      <TabsContent value="skipped">
        <ImportSkippedList :rows="skipped" />
      </TabsContent>
    </Tabs>

    <section
      :aria-label="t('imports.review.summary')"
      class="flex flex-wrap items-center gap-x-6 gap-y-3 rounded-lg border border-border bg-card p-4"
    >
      <span class="font-medium" aria-live="polite">{{
        t('imports.review.included', { n: sums.count }, sums.count)
      }}</span>
      <span class="text-sm text-muted-foreground">
        {{ t('imports.review.income') }}
        <MoneyText :cents="sums.incomeCents" kind="INCOME" class="font-medium" />
      </span>
      <span class="text-sm text-muted-foreground">
        {{ t('imports.review.expense') }}
        <MoneyText :cents="sums.expenseCents" kind="EXPENSE" class="font-medium" />
      </span>
      <p v-if="sums.count && !ready" role="status" class="text-sm text-destructive">
        {{ t('imports.review.missingCategory') }}
      </p>
      <Button class="ml-auto" :disabled="!ready || pending" @click="emit('submit')">
        {{ t('imports.review.submit', { n: sums.count }, sums.count) }}
      </Button>
    </section>
  </section>
</template>
