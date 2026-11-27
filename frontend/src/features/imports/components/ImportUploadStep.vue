<script setup lang="ts">
import { ChevronRight } from '@lucide/vue'
import { useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/shared/ui/button'
import { Card, CardContent } from '@/shared/ui/card'
import FileInput from '@/shared/ui/FileInput.vue'
import SelectField from '@/shared/ui/SelectField.vue'

/**
 * Step 1: destination account and CSV file; after a preview, the other products' mapping and, when
 * pocket moves were skipped for want of it, the pockets account.
 */
const props = defineProps<{
  /** Active bank accounts. */
  accounts: { id: string; label: string }[]
  /** Products besides the default one, with the account they map to (null = ignored). */
  products: { product: string; bankAccountId: string | null }[]
  /** Ask for the pockets account: shown while it is missing, and once chosen. */
  askPockets: boolean
  pocketsBankAccountId: string | null
  /** A preview is running. */
  pending: boolean
  /** A commit is running: nothing here may change until it settles. */
  locked: boolean
  /** A preview is under review: only a new file, account or mapping previews again. */
  reviewing: boolean
  error: string | null
}>()
const bankAccountId = defineModel<string>('bankAccountId', { required: true })
const file = defineModel<File | null>('file', { required: true })
const emit = defineEmits<{
  submit: []
  map: [product: string, bankAccountId: string | null]
  pockets: [bankAccountId: string]
}>()

const { t } = useI18n()
const pocketsHintId = useId()

function onSubmit() {
  if (!props.reviewing && !props.locked) emit('submit')
}
</script>

<template>
  <Card>
    <CardContent>
      <form class="grid gap-4" novalidate @submit.prevent="onSubmit">
        <p v-if="error" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
          {{ error }}
        </p>

        <div class="grid gap-4 sm:grid-cols-2">
          <SelectField
            v-model="bankAccountId"
            :label="t('imports.upload.bankAccount')"
            :options="accounts"
            :placeholder="t('imports.upload.choose')"
            :disabled="locked"
          />
          <FileInput
            v-model="file"
            :label="t('imports.upload.file')"
            accept=".csv,text/csv"
            :disabled="locked"
          />
        </div>

        <details class="group text-sm">
          <summary
            class="flex w-fit cursor-pointer list-none items-center gap-1 font-medium text-primary"
          >
            <ChevronRight
              class="size-4 shrink-0 transition-transform group-open:rotate-90"
              aria-hidden="true"
            />
            {{ t('imports.upload.helpTitle') }}
          </summary>
          <p class="mt-2 text-muted-foreground">{{ t('imports.upload.help') }}</p>
        </details>

        <fieldset v-if="products.length" class="grid gap-3 rounded-md border border-border p-4">
          <legend class="px-1 text-sm font-medium">{{ t('imports.upload.mappingTitle') }}</legend>
          <p class="text-sm text-muted-foreground">{{ t('imports.upload.mappingHint') }}</p>
          <SelectField
            v-for="p in products"
            :key="p.product"
            :model-value="p.bankAccountId ?? ''"
            :label="p.product"
            :options="accounts"
            :empty-label="t('imports.upload.ignore')"
            :disabled="pending || locked"
            @update:model-value="emit('map', p.product, $event || null)"
          />
        </fieldset>

        <div v-if="askPockets" class="grid gap-2 rounded-md border border-border p-4">
          <SelectField
            :model-value="pocketsBankAccountId ?? ''"
            :label="t('imports.upload.pocketsAccount')"
            :options="accounts"
            :placeholder="t('imports.upload.choose')"
            :disabled="pending || locked"
            :aria-describedby="pocketsHintId"
            @update:model-value="$event && emit('pockets', $event)"
          />
          <p :id="pocketsHintId" class="text-sm text-muted-foreground">
            {{ t('imports.upload.pocketsHint') }}
          </p>
        </div>

        <div v-if="!reviewing" class="flex justify-end">
          <Button type="submit" :disabled="!bankAccountId || !file || pending">
            {{ pending ? t('imports.upload.loading') : t('imports.upload.submit') }}
          </Button>
        </div>
      </form>
    </CardContent>
  </Card>
</template>
