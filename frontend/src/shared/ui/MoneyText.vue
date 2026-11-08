<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatMoney, type Locale } from '@/shared/lib/money'

const props = defineProps<{
  cents: number
  kind?: 'INCOME' | 'EXPENSE' | 'TRANSFER' | 'NEUTRAL'
}>()

const { locale } = useI18n()

const text = computed(() => {
  const money = formatMoney(props.cents, locale.value as Locale)
  return props.kind === 'INCOME' ? `+${money}` : money
})
</script>

<template>
  <span
    class="tabular-nums"
    :class="{ 'text-income': kind === 'INCOME', 'text-expense': kind === 'EXPENSE' }"
    >{{ text }}</span
  >
</template>
