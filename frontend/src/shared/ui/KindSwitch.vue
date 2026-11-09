<script setup lang="ts">
import { useId } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TransactionKind } from '@/shared/api/types'

defineProps<{ legend: string }>()
const model = defineModel<TransactionKind | undefined>({ required: true })

const { t } = useI18n()
const name = useId()
const KINDS = ['EXPENSE', 'INCOME', 'TRANSFER'] as const satisfies readonly TransactionKind[]
</script>

<template>
  <fieldset class="grid gap-2">
    <legend class="mb-2 text-sm font-medium text-foreground">{{ legend }}</legend>
    <div class="grid grid-cols-3 gap-1 rounded-md bg-muted p-1">
      <label
        v-for="k in KINDS"
        :key="k"
        class="flex cursor-pointer items-center justify-center rounded-sm px-2 py-1 text-sm font-medium text-muted-foreground has-checked:bg-background has-checked:text-foreground has-checked:shadow-xs has-focus-visible:ring-3 has-focus-visible:ring-ring/50"
      >
        <input v-model="model" type="radio" :name="name" :value="k" class="sr-only" />
        {{ t(`transactions.kind.${k}`) }}
      </label>
    </div>
  </fieldset>
</template>
