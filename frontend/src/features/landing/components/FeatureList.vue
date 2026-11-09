<script setup lang="ts">
import { CalendarClock, PiggyBank, Users } from '@lucide/vue'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const items = [
  { key: 'shared', icon: Users },
  { key: 'recurring', icon: CalendarClock },
  { key: 'budgets', icon: PiggyBank },
] as const
</script>

<template>
  <ul class="grid gap-4 md:grid-cols-3">
    <li v-for="item in items" :key="item.key" class="rounded-lg border bg-card p-4">
      <span class="flex size-10 items-center justify-center rounded-md bg-muted text-primary">
        <component :is="item.icon" class="size-5" aria-hidden="true" />
      </span>
      <h2 class="mt-3 font-medium text-foreground">
        {{ t(`landing.highlights.${item.key}.title`) }}
      </h2>
      <p class="mt-1 text-sm text-muted-foreground">
        {{ t(`landing.highlights.${item.key}.text`) }}
      </p>
    </li>
  </ul>
</template>
