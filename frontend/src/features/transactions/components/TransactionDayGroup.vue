<script setup lang="ts">
import { computed, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TransactionResponse } from '@/shared/api/types'
import { formatBusinessDate } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import MoneyText from '@/shared/ui/MoneyText.vue'
import TransactionRow from './TransactionRow.vue'

const props = defineProps<{ date: string; rows: TransactionResponse[] }>()
const emit = defineEmits<{ edit: [transaction: TransactionResponse] }>()

const { t, locale } = useI18n()
const headingId = useId()

/** Income − expense of the day; transfers only move money between own accounts. */
const net = computed(() =>
  props.rows.reduce(
    (sum, r) =>
      r.kind === 'INCOME' ? sum + r.amountCents : r.kind === 'EXPENSE' ? sum - r.amountCents : sum,
    0,
  ),
)
</script>

<template>
  <li class="rounded-lg border border-border bg-card">
    <div class="flex items-center justify-between gap-4 border-b border-border px-4 py-2">
      <h2 :id="headingId" class="text-sm font-semibold text-foreground">
        {{ formatBusinessDate(date, locale as Locale, 'long') }}
      </h2>
      <span class="text-sm text-muted-foreground">
        <span class="sr-only">{{ t('transactions.dayNet') }}: </span>
        <MoneyText :cents="net" kind="NEUTRAL" signed :data-testid="`day-net-${date}`" />
      </span>
    </div>
    <ul :aria-labelledby="headingId" class="grid p-1">
      <TransactionRow
        v-for="row in rows"
        :key="row.id"
        :transaction="row"
        @edit="emit('edit', row)"
      />
    </ul>
  </li>
</template>
