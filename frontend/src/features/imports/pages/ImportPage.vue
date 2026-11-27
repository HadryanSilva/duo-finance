<script setup lang="ts">
import { useQueryClient } from '@tanstack/vue-query'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useBankAccounts } from '@/shared/api/queries/bank-accounts'
import { isApiError } from '@/shared/api/problem'
import type { CommitResponse, PreviewResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import PageHeader from '@/shared/ui/PageHeader.vue'
import {
  useCommitImport,
  usePreviewImport,
  type PreviewInput,
  type ProductMapping,
} from '../api/imports-api'
import ImportHistory from '../components/ImportHistory.vue'
import ImportResultStep from '../components/ImportResultStep.vue'
import ImportReviewStep from '../components/ImportReviewStep.vue'
import ImportUploadStep from '../components/ImportUploadStep.vue'
import { initReview, rowErrorsFrom, toDecisions, type ReviewRow } from '../lib/review-state'

/** Mirrors the backend's ImportContextLoader.DEFAULT_PRODUCT: it always goes to the chosen account. */
const DEFAULT_PRODUCT = 'Current'

const { t } = useI18n()
const route = useRoute()

// upload -> review -> result. The File stays here, in memory, for the commit.
const step = ref<'upload' | 'review' | 'result'>('upload')
const bankAccountId = ref('')
const file = ref<File | null>(null)
/** Bumped to remount the upload form, which clears its file input. */
const uploadKey = ref(0)
/** Product mapping the current preview was made with; the commit must send the same one. */
const mapping = ref<ProductMapping | undefined>()
const preview = ref<PreviewResponse | null>(null)
const rows = ref<ReviewRow[]>([])
/** Bumped on each preview: a new plan drops any pending review prompt. */
const previewKey = ref(0)
const result = ref<CommitResponse | null>(null)
const uploadError = ref<string | null>(null)
const reviewError = ref<string | null>(null)
/** The last commit's per-row errors by rowId; a new preview starts clean. */
const rowErrors = ref<Record<string, string>>({})

/** Id of the latest preview request: only its response may land. */
let previewSeq = 0
const previewImport = usePreviewImport()
const commitImport = useCommitImport()
const queryClient = useQueryClient()

const allAccounts = useBankAccounts(true)
const accountOptions = computed(() =>
  (allAccounts.data.value ?? [])
    .filter((a) => !a.archived)
    .map((a) => ({ id: a.id, label: a.name })),
)
/** A link such as the dashboard's stale-import notice may name the account; only an active one is taken, once. */
let preselected = false
watch(
  accountOptions,
  (options) => {
    if (preselected || options.length === 0) return
    preselected = true
    const wanted = route.query.bankAccountId
    if (
      bankAccountId.value === '' &&
      typeof wanted === 'string' &&
      options.some((o) => o.id === wanted)
    )
      bankAccountId.value = wanted
  },
  { immediate: true },
)
/**
 * A refetch may drop the chosen account (archived meanwhile, in another tab or by the partner): a native select
 * would show the placeholder while still holding the id, so the choice is cleared before an upload can use it.
 */
watch(accountOptions, (options) => {
  if (step.value !== 'upload' || bankAccountId.value === '' || !allAccounts.data.value) return
  if (!options.some((o) => o.id === bankAccountId.value)) bankAccountId.value = ''
})
const accountName = (id: string | null) =>
  allAccounts.data.value?.find((a) => a.id === id)?.name ?? ''

const otherProducts = computed(() =>
  (preview.value?.products ?? []).filter((p) => p.product !== DEFAULT_PRODUCT),
)
// A different file or account is a different import: any preview in flight is dropped and the
// review starts over.
watch([file, bankAccountId], () => {
  previewSeq++
  if (step.value !== 'review') return
  step.value = 'upload'
  preview.value = null
  mapping.value = undefined
})

function importError(e: unknown): string {
  if (isApiError(e) && e.status === 413) return t('imports.errors.tooLarge')
  if (isApiError(e) && e.code === 'IMPORT_ROW_INVALID' && e.line !== undefined)
    return t('imports.errors.rowInvalidLine', { line: e.line })
  return errorMessage(e)
}

function input(productMapping: ProductMapping | undefined): PreviewInput | null {
  if (!file.value || !bankAccountId.value) return null
  return {
    file: file.value,
    bankAccountId: bankAccountId.value,
    productMapping,
  }
}

async function runPreview(productMapping = mapping.value) {
  if (commitImport.isPending.value) return
  const body = input(productMapping)
  if (!body) return
  const seq = ++previewSeq
  // the file or account changed, or a newer preview was asked for, while this one was in flight
  const stale = () =>
    seq !== previewSeq || file.value !== body.file || bankAccountId.value !== body.bankAccountId
  uploadError.value = null
  reviewError.value = null
  try {
    const response = await previewImport.mutateAsync(body)
    if (stale()) return
    preview.value = response
    mapping.value = productMapping
    rows.value = initReview(response.rows)
    rowErrors.value = {}
    previewKey.value++
    step.value = 'review'
  } catch (e) {
    if (!stale()) uploadError.value = importError(e)
  }
}

function onMap(product: string, accountId: string | null) {
  // keep the whole effective mapping so a re-preview does not reset the other products
  const next: ProductMapping = {}
  for (const p of otherProducts.value) next[p.product] = p.bankAccountId
  next[product] = accountId
  void runPreview(next)
}

/** The file on disk no longer matches what was reviewed: it has to be chosen again. */
function backToUpload(message: string | null) {
  previewSeq++
  step.value = 'upload'
  preview.value = null
  file.value = null
  mapping.value = undefined
  uploadKey.value++
  uploadError.value = message
}

async function runCommit() {
  const body = input(mapping.value)
  if (!body) return
  reviewError.value = null
  rowErrors.value = {}
  try {
    result.value = await commitImport.mutateAsync({ ...body, decisions: toDecisions(rows.value) })
    step.value = 'result'
  } catch (e) {
    if (isApiError(e) && e.fieldErrors.some((f) => f.code === 'ROW_UNKNOWN')) {
      backToUpload(t('imports.errors.fileChanged'))
      return
    }
    const byRow = isApiError(e) ? rowErrorsFrom(e.fieldErrors) : {}
    const n = Object.keys(byRow).length
    if (n) {
      rowErrors.value = byRow
      // A category was archived meanwhile: reload the lists so it is no longer offered.
      if (Object.values(byRow).includes('CATEGORY_ARCHIVED'))
        void queryClient.invalidateQueries({ queryKey: ['categories'] })
      // A goal was completed or deleted meanwhile: reload the goals so it is no longer offered.
      if (Object.values(byRow).includes('GOAL_INVALID'))
        void queryClient.invalidateQueries({ queryKey: ['goals'] })
      reviewError.value = t('imports.errors.rowsNeedAttention', { n }, n)
    } else {
      reviewError.value = importError(e)
    }
  }
}

function clearRowError(rowId: string) {
  const rest = { ...rowErrors.value }
  delete rest[rowId]
  rowErrors.value = rest
}

function restart() {
  backToUpload(null)
  result.value = null
}
</script>

<template>
  <PageHeader :title="t('imports.title')" :subtitle="t('imports.subtitle')" />

  <ImportResultStep v-if="step === 'result' && result" :result="result" @again="restart" />
  <template v-else>
    <ImportUploadStep
      :key="uploadKey"
      v-model:bank-account-id="bankAccountId"
      v-model:file="file"
      :accounts="accountOptions"
      :products="step === 'review' ? otherProducts : []"
      :pending="previewImport.isPending.value"
      :locked="commitImport.isPending.value"
      :reviewing="step === 'review'"
      :error="uploadError"
      @submit="runPreview()"
      @map="onMap"
    />
    <ImportReviewStep
      v-if="step === 'review' && preview"
      :key="previewKey"
      v-model:rows="rows"
      :skipped="preview.skipped.rows"
      :account-name="accountName"
      :pending="previewImport.isPending.value || commitImport.isPending.value"
      :error="reviewError"
      :row-errors="rowErrors"
      @clear-row-error="clearRowError"
      @submit="runCommit"
    />
  </template>

  <ImportHistory :account-name="accountName" />
</template>
