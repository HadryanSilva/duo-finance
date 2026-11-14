<script setup lang="ts">
import { useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/shared/ui/button'
import { Card, CardContent } from '@/shared/ui/card'
import { Label } from '@/shared/ui/label'
import SelectField from '@/shared/ui/SelectField.vue'

/** Step 1: destination account and CSV file; after a preview, the other products' mapping. */
const props = defineProps<{
  /** Active bank accounts. */
  accounts: { id: string; label: string }[]
  /** Products besides the default one, with the account they map to (null = ignored). */
  products: { product: string; bankAccountId: string | null }[]
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
const emit = defineEmits<{ submit: []; map: [product: string, bankAccountId: string | null] }>()

const { t } = useI18n()
const fileId = useId()

function onSubmit() {
  if (!props.reviewing && !props.locked) emit('submit')
}

function onFile(event: Event) {
  file.value = (event.target as HTMLInputElement).files?.[0] ?? null
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
          <div class="grid gap-2">
            <Label :for="fileId">{{ t('imports.upload.file') }}</Label>
            <input
              :id="fileId"
              type="file"
              accept=".csv,text/csv"
              :disabled="locked"
              class="text-sm file:mr-3 file:rounded-md file:border file:border-input file:bg-background file:px-3 file:py-1.5 file:text-sm"
              @change="onFile"
            />
          </div>
        </div>

        <details class="text-sm">
          <summary class="cursor-pointer font-medium text-primary">
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

        <div v-if="!reviewing" class="flex justify-end">
          <Button type="submit" :disabled="!bankAccountId || !file || pending">
            {{ pending ? t('imports.upload.loading') : t('imports.upload.submit') }}
          </Button>
        </div>
      </form>
    </CardContent>
  </Card>
</template>
