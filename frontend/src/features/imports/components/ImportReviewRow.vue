<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatBusinessDate } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { Badge } from '@/shared/ui/badge'
import { Button } from '@/shared/ui/button'
import MoneyText from '@/shared/ui/MoneyText.vue'
import type { ReviewRow } from '../lib/review-state'

/** One planned row of the review: include checkbox, amount and category (none for a transfer). */
const props = defineProps<{
  row: ReviewRow
  /** Active categories of the row's kind. */
  categories: { id: string; label: string }[]
  accountName: (id: string | null) => string
  /** Set when the category just changed and other rows share the description. */
  applyCount?: number
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

const SELECT =
  'h-9 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:opacity-50 sm:w-56'
</script>

<template>
  <li
    class="grid gap-3 rounded-lg border border-border bg-card p-3"
    :class="{ 'opacity-60': locked || !row.include }"
  >
    <div class="flex flex-wrap items-center gap-3">
      <input
        type="checkbox"
        class="size-4 accent-primary"
        :checked="row.include"
        :disabled="locked"
        :aria-label="t('imports.review.include', { description: row.description })"
        @change="emit('include', ($event.target as HTMLInputElement).checked)"
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
      <select
        v-if="!isTransfer"
        :class="SELECT"
        :value="row.categoryId ?? ''"
        :disabled="locked"
        :aria-label="t('imports.review.category', { description: row.description })"
        :aria-invalid="row.include && !row.categoryId ? 'true' : undefined"
        @change="emit('category', ($event.target as HTMLSelectElement).value)"
      >
        <option value="" disabled>{{ t('imports.review.chooseCategory') }}</option>
        <option v-for="c in categories" :key="c.id" :value="c.id">{{ c.label }}</option>
      </select>
    </div>
    <div
      v-if="applyCount"
      class="flex flex-wrap items-center gap-2 rounded-md bg-muted p-2 text-sm"
      role="group"
    >
      <span class="flex-1">{{
        t('imports.review.applyPrompt', { n: applyCount }, applyCount)
      }}</span>
      <Button size="sm" @click="emit('apply')">{{ t('imports.review.apply') }}</Button>
      <Button size="sm" variant="ghost" @click="emit('dismiss')">
        {{ t('imports.review.dismiss') }}
      </Button>
    </div>
  </li>
</template>
