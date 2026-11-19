<script setup lang="ts">
import { computed, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import type { TransactionResponse } from '@/shared/api/types'
import { formatBusinessDate } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import MoneyText from '@/shared/ui/MoneyText.vue'
import TransactionRow from './TransactionRow.vue'

/** `bankAccountId`: the list is filtered by this account, so transfers into or out of it count. */
const props = defineProps<{ date: string; rows: TransactionResponse[]; bankAccountId?: string }>()
const emit = defineEmits<{ edit: [transaction: TransactionResponse] }>()

const { t, locale } = useI18n()
const headingId = useId()

/**
 * Income − expense of the day. Transfers only move money between own accounts, so they count only
 * when the list is filtered by one: into it as income, out of it as expense (as the period totals).
 */
function signed(r: TransactionResponse): number {
  if (r.kind === 'INCOME') return r.amountCents
  if (r.kind === 'EXPENSE') return -r.amountCents
  const account = props.bankAccountId
  if (!account) return 0
  if (r.destinationBankAccount?.id === account) return r.amountCents
  if (r.bankAccount?.id === account) return -r.amountCents
  return 0
}
const net = computed(() => props.rows.reduce((sum, r) => sum + signed(r), 0))
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
