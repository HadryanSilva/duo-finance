<script setup lang="ts">
import Checkbox from '@/shared/ui/Checkbox.vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatBusinessDate } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { Badge } from '@/shared/ui/badge'
import { Button } from '@/shared/ui/button'
import MoneyText from '@/shared/ui/MoneyText.vue'
import type { ReviewRow } from '../lib/review-state'
import NativeSelect from '@/shared/ui/NativeSelect.vue'

/** One planned row of the review: include checkbox, amount and category (none for a transfer). */
const props = defineProps<{
  row: ReviewRow
  /** Active categories of the row's kind. */
  categories: { id: string; label: string }[]
  accountName: (id: string | null) => string
  /** Set when the category just changed and other rows share the description. */
  applyCount?: number
  /**
   * The commit rejected this row: the translated message, and whether unticking is the fix (the
   * checkbox is then the invalid control) rather than choosing another category.
   */
  error?: { message: string; onInclude: boolean }
}>()
const emit = defineEmits<{
  include: [include: boolean]
  category: [categoryId: string]
  apply: []
  dismiss: []
}>()

const { t, locale } = useI18n()
const locked = computed(() => props.row.status === 'ALREADY_IMPORTED')
const isTransfer = computed(() => props.row.kind === 'TRANSFER')
const date = computed(() => formatBusinessDate(props.row.date, locale.value as Locale))
const errorId = computed(() => `row-error-${props.row.rowId}`)
const includeInvalid = computed(() => !!props.error && (props.error.onInclude || isTransfer.value))
const categoryInvalid = computed(
  () => (!!props.error && !includeInvalid.value) || (props.row.include && !props.row.categoryId),
)
</script>

<template>
  <li
    class="grid gap-3 rounded-lg border border-border bg-card p-3"
    :class="{ 'opacity-60': locked || !row.include }"
  >
    <div class="flex flex-wrap items-center gap-3">
      <Checkbox
        :model-value="row.include"
        :disabled="locked"
        :aria-label="t('imports.review.include', { description: row.description })"
        :aria-invalid="includeInvalid ? 'true' : undefined"
        :aria-describedby="includeInvalid ? errorId : undefined"
        @update:model-value="emit('include', !!$event)"
      />
      <div class="min-w-0 flex-1">
        <div class="flex flex-wrap items-center gap-2">
          <span class="truncate font-medium text-foreground">{{ row.description }}</span>
          <Badge v-if="locked" variant="secondary">{{ t('imports.review.alreadyImported') }}</Badge>
        </div>
        <p class="text-sm text-muted-foreground">
          <span>{{ date }}</span>
          <template v-if="isTransfer">
            <span aria-hidden="true"> · </span>
            <span>{{
              t('imports.review.transferRoute', {
                from: accountName(row.bankAccountId),
                to: accountName(row.destinationBankAccountId),
              })
            }}</span>
          </template>
        </p>
      </div>
      <MoneyText :cents="row.amountCents" :kind="row.kind" class="font-semibold" />
      <NativeSelect
        v-if="!isTransfer"
        class="sm:w-56"
        :model-value="row.categoryId ?? ''"
        :disabled="locked"
        :aria-label="t('imports.review.category', { description: row.description })"
        :aria-invalid="categoryInvalid ? 'true' : undefined"
        :aria-describedby="error && !includeInvalid ? errorId : undefined"
        @update:model-value="emit('category', $event ?? '')"
      >
        <option value="" disabled>{{ t('imports.review.chooseCategory') }}</option>
        <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.label }}</option>
      </NativeSelect>
    </div>
    <p v-if="error" :id="errorId" class="text-sm text-destructive">{{ error.message }}</p>
    <div
      v-if="applyCount"
      class="flex flex-wrap items-center gap-2 rounded-md bg-muted p-2 text-sm"
      role="group"
      aria-live="polite"
      :aria-labelledby="`apply-prompt-${row.rowId}`"
    >
      <span :id="`apply-prompt-${row.rowId}`" class="flex-1">{{
        t('imports.review.applyPrompt', { n: applyCount }, applyCount)
      }}</span>
      <Button size="sm" @click="emit('apply')">{{ t('imports.review.apply') }}</Button>
      <Button size="sm" variant="ghost" @click="emit('dismiss')">
        {{ t('imports.review.dismiss') }}
      </Button>
    </div>
  </li>
</template>
