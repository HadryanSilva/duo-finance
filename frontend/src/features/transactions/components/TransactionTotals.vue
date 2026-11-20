<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useTransactionTotals, type TransactionFilter } from '@/shared/api/queries/transactions'
import { errorMessage } from '@/shared/i18n/error-codes'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { Skeleton } from '@/shared/ui/skeleton'

/** The list's active filter: the totals cover every row it matches, not only the loaded pages. */
const props = defineProps<{ filter: TransactionFilter }>()

const { t } = useI18n()
const totals = useTransactionTotals(() => props.filter)

/** Without an account to move money in or out of, transfers have no income or expense. */
const transfersOnly = computed(
  () => props.filter.kind === 'TRANSFER' && !props.filter.bankAccountId,
)
</script>

<template>
  <div v-if="totals.isPending.value" class="mb-4 grid gap-4 sm:grid-cols-3">
    <Skeleton class="h-12 w-full" />
    <Skeleton class="h-12 w-full" />
    <Skeleton class="h-12 w-full" />
  </div>
  <p v-else-if="totals.isError.value" role="alert" class="mb-4 text-sm text-destructive">
    {{ errorMessage(totals.error.value) }}
  </p>
  <template v-else-if="totals.data.value && totals.data.value.count > 0">
    <p v-if="transfersOnly" class="mb-4 text-sm text-muted-foreground">
      {{
        t('transactions.totals.transfers', { n: totals.data.value.count }, totals.data.value.count)
      }}
    </p>
    <div
      v-else
      role="group"
      :aria-label="t('transactions.totals.label')"
      class="mb-4 rounded-lg border border-border bg-card p-4"
    >
      <dl class="grid gap-4 sm:grid-cols-3">
        <div>
          <dt class="text-sm text-muted-foreground">{{ t('transactions.totals.in') }}</dt>
          <dd class="text-xl font-semibold">
            <MoneyText :cents="totals.data.value.incomeCents" kind="INCOME" />
          </dd>
        </div>
        <div>
          <dt class="text-sm text-muted-foreground">{{ t('transactions.totals.out') }}</dt>
          <dd class="text-xl font-semibold">
            <MoneyText :cents="totals.data.value.expenseCents" kind="EXPENSE" />
          </dd>
        </div>
        <div>
          <dt class="text-sm text-muted-foreground">{{ t('transactions.totals.net') }}</dt>
          <dd
            data-testid="totals-net"
            class="text-xl font-semibold"
            :class="totals.data.value.netCents < 0 ? 'text-expense' : 'text-foreground'"
          >
            <MoneyText :cents="totals.data.value.netCents" signed />
          </dd>
          <dd class="text-sm text-muted-foreground">
            {{
              t(
                'transactions.totals.count',
                { n: totals.data.value.count },
                totals.data.value.count,
              )
            }}
          </dd>
        </div>
      </dl>
    </div>
  </template>
</template>
