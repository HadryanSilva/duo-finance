<script setup lang="ts">
import { CircleCheck } from '@lucide/vue'
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import type { CommitResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import { Card, CardContent } from '@/shared/ui/card'
import ConfirmDialog from '@/shared/ui/ConfirmDialog.vue'
import { useUndoImport } from '../api/imports-api'

/** Step 3: what the commit created, with links to the transactions and to undo the batch. */
const props = defineProps<{ result: CommitResponse }>()
const emit = defineEmits<{ again: [] }>()

const { t } = useI18n()
const undo = useUndoImport()
const confirming = ref(false)
const undone = ref(false)
const error = ref<string | null>(null)

async function onUndo() {
  const batchId = props.result.batchId
  if (!batchId) return
  error.value = null
  try {
    await undo.mutateAsync(batchId)
    undone.value = true
  } catch (e) {
    error.value = errorMessage(e)
  }
}
</script>

<template>
  <Card>
    <CardContent class="grid gap-4">
      <div class="flex items-start gap-3">
        <CircleCheck class="size-6 shrink-0 text-income" aria-hidden="true" />
        <div>
          <h2 class="text-lg font-semibold">{{ t('imports.result.title') }}</h2>
          <p class="text-muted-foreground">
            {{
              t('imports.result.summary', {
                created: t('imports.result.created', { n: result.created }, result.created),
                skipped: t(
                  'imports.result.skipped',
                  { n: result.skippedDuplicates },
                  result.skippedDuplicates,
                ),
              })
            }}
          </p>
        </div>
      </div>
      <p v-if="error" role="alert" class="text-sm text-destructive">{{ error }}</p>
      <p v-if="undone" role="status" class="text-sm">{{ t('imports.result.undone') }}</p>
      <div class="flex flex-wrap gap-2">
        <Button as-child>
          <RouterLink to="/transactions">{{ t('imports.result.viewTransactions') }}</RouterLink>
        </Button>
        <Button
          v-if="result.batchId && !undone"
          variant="outline"
          :disabled="undo.isPending.value"
          @click="confirming = true"
        >
          {{ t('imports.result.undo') }}
        </Button>
        <Button variant="ghost" @click="emit('again')">{{ t('imports.result.another') }}</Button>
      </div>
    </CardContent>
  </Card>

  <ConfirmDialog
    v-model:open="confirming"
    :title="t('imports.undo.title')"
    :message="t('imports.undo.message')"
    :confirm-label="t('imports.undo.confirm')"
    destructive
    @confirm="onUndo"
  />
</template>
