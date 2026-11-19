<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { RecurringRuleResponse } from '@/shared/api/types'
import { fixedMonthly } from '@/shared/lib/figures'
import MoneyText from '@/shared/ui/MoneyText.vue'

/** The active, unfinished rules; transfers are left out here. */
const props = defineProps<{ rules: RecurringRuleResponse[] }>()

const { t } = useI18n()

const counted = computed(() => props.rules.filter((r) => r.kind !== 'TRANSFER'))
const fixed = computed(() => fixedMonthly(counted.value))
const short = computed(() => fixed.value.netCents < 0)
</script>

<template>
  <div
    v-if="counted.length"
    role="group"
    :aria-label="t('recurring.summary.label')"
    class="rounded-lg border border-border bg-card p-4"
  >
    <dl class="grid gap-4 sm:grid-cols-3">
      <div>
        <dt class="text-sm text-muted-foreground">{{ t('recurring.summary.in') }}</dt>
        <dd class="text-xl font-semibold">
          <MoneyText :cents="fixed.inCents" kind="INCOME" />
        </dd>
      </div>
      <div>
        <dt class="text-sm text-muted-foreground">{{ t('recurring.summary.out') }}</dt>
        <dd class="text-xl font-semibold">
          <MoneyText :cents="fixed.outCents" kind="EXPENSE" />
        </dd>
      </div>
      <div>
        <dt class="text-sm text-muted-foreground">
          {{ short ? t('recurring.summary.short') : t('recurring.summary.left') }}
        </dt>
        <dd
          data-testid="fixed-net"
          class="text-xl font-semibold"
          :class="short ? 'text-expense' : 'text-foreground'"
        >
          <MoneyText :cents="Math.abs(fixed.netCents)" />
        </dd>
      </div>
    </dl>
  </div>
</template>
