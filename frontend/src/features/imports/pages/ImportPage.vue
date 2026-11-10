<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
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
import { initReview, toDecisions, type ReviewRow } from '../lib/review-state'

/** Mirrors the backend's ImportContextLoader.DEFAULT_PRODUCT: it always goes to the chosen account. */
const DEFAULT_PRODUCT = 'Current'

const { t } = useI18n()

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

const previewImport = usePreviewImport()
const commitImport = useCommitImport()

const allAccounts = useBankAccounts(true)
const accountOptions = computed(() =>
  (allAccounts.data.value ?? [])
    .filter((a) => !a.archived)
    .map((a) => ({ id: a.id, label: a.name })),
)
const accountName = (id: string | null) =>
  allAccounts.data.value?.find((a) => a.id === id)?.name ?? ''

const otherProducts = computed(() =>
  (preview.value?.products ?? []).filter((p) => p.product !== DEFAULT_PRODUCT),
)

// A different file or account is a different import: the review starts over.
watch([file, bankAccountId], () => {
  if (step.value !== 'review') return
  step.value = 'upload'
  preview.value = null
  mapping.value = undefined
})

function importError(e: unknown): string {
  if (isApiError(e) && e.status === 413) return t('imports.errors.tooLarge')
  return errorMessage(e)
}

function input(productMapping: ProductMapping | undefined): PreviewInput | null {
  if (!file.value || !bankAccountId.value) return null
  return { file: file.value, bankAccountId: bankAccountId.value, productMapping }
}

async function runPreview(productMapping = mapping.value) {
  const body = input(productMapping)
  if (!body) return
  uploadError.value = null
  reviewError.value = null
  try {
    const response = await previewImport.mutateAsync(body)
    preview.value = response
    mapping.value = productMapping
    rows.value = initReview(response.rows)
    previewKey.value++
    step.value = 'review'
  } catch (e) {
    uploadError.value = importError(e)
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
  try {
    result.value = await commitImport.mutateAsync({ ...body, decisions: toDecisions(rows.value) })
    step.value = 'result'
  } catch (e) {
    if (isApiError(e) && e.fieldErrors.some((f) => f.code === 'ROW_UNKNOWN')) {
      backToUpload(t('imports.errors.fileChanged'))
    } else {
      reviewError.value = importError(e)
    }
  }
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
      @submit="runCommit"
    />
  </template>

  <ImportHistory :account-name="accountName" />
</template>
