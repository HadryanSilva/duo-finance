<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import type { ImportBatchResponse } from '@/shared/api/types'
import { formatInstant } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { errorMessage } from '@/shared/i18n/error-codes'
import AuthorAvatar from '@/shared/ui/AuthorAvatar.vue'
import { Badge } from '@/shared/ui/badge'
import { Button } from '@/shared/ui/button'
import ConfirmDialog from '@/shared/ui/ConfirmDialog.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import { useImportBatches, useUndoImport } from '../api/imports-api'

/** The Account's past imports, newest first; each batch still active can be undone. */
defineProps<{ accountName: (id: string | null) => string }>()

const { t, locale } = useI18n()
const page = ref(0)
const batches = useImportBatches(page)
const undo = useUndoImport()
const content = computed(() => batches.data.value?.content ?? [])
const totalPages = computed(() => batches.data.value?.totalPages ?? 1)

const when = (b: ImportBatchResponse) => formatInstant(b.createdAt, locale.value as Locale)
const author = (b: ImportBatchResponse) =>
  b.createdBy ? { userId: b.createdBy, displayName: b.createdByName ?? '' } : null

const target = ref<ImportBatchResponse | null>(null)
const confirming = computed({
  get: () => target.value !== null,
  set: (open: boolean) => {
    if (!open) target.value = null
  },
})
const error = ref<string | null>(null)

async function onUndo(batch: ImportBatchResponse) {
  error.value = null
  try {
    await undo.mutateAsync(batch.id)
  } catch (e) {
    error.value = errorMessage(e)
  }
}
</script>

<template>
  <section class="mt-10 grid gap-3">
    <h2 class="text-lg font-semibold">{{ t('imports.history.title') }}</h2>
    <p v-if="error" role="alert" class="text-sm text-destructive">{{ error }}</p>

    <div v-if="batches.isPending.value" class="grid gap-2">
      <Skeleton class="h-14 w-full" />
      <Skeleton class="h-14 w-full" />
    </div>
    <p v-else-if="batches.isError.value" role="alert" class="text-sm text-destructive">
      {{ errorMessage(batches.error.value) }}
    </p>
    <p v-else-if="!content.length" class="text-sm text-muted-foreground">
      {{ t('imports.history.empty') }}
    </p>
    <ul v-else class="grid gap-2" :aria-label="t('imports.history.title')">
      <li
        v-for="b in content"
        :key="b.id"
        class="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-card p-3"
      >
        <AuthorAvatar :author="author(b)" />
        <div class="min-w-0 flex-1">
          <div class="flex flex-wrap items-center gap-2">
            <span class="font-medium text-foreground">{{ when(b) }}</span>
            <Badge v-if="b.undoneAt" variant="secondary">{{ t('imports.history.undone') }}</Badge>
          </div>
          <p class="text-sm text-muted-foreground">
            <span>{{ t('imports.history.rows', { n: b.rowCount }, b.rowCount) }}</span>
            <template v-if="b.bankAccountId">
              <span aria-hidden="true"> · </span>
              <span>{{ accountName(b.bankAccountId) }}</span>
            </template>
          </p>
        </div>
        <Button
          v-if="!b.undoneAt"
          size="sm"
          variant="outline"
          :disabled="undo.isPending.value"
          :aria-label="t('imports.history.undoLabel', { date: when(b) })"
          @click="target = b"
        >
          {{ t('imports.undo.confirm') }}
        </Button>
      </li>
    </ul>

    <div v-if="totalPages > 1" class="flex justify-center gap-2">
      <Button variant="outline" size="sm" :disabled="page === 0" @click="page--">
        {{ t('imports.history.previous') }}
      </Button>
      <Button variant="outline" size="sm" :disabled="page >= totalPages - 1" @click="page++">
        {{ t('imports.history.next') }}
      </Button>
    </div>

    <ConfirmDialog
      v-model:open="confirming"
      :title="t('imports.undo.title')"
      :message="t('imports.undo.message')"
      :confirm-label="t('imports.undo.confirm')"
      destructive
      @confirm="target && onUndo(target)"
    />
  </section>
</template>
