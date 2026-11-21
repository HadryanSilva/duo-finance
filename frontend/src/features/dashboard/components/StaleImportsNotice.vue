<script setup lang="ts">
import { TriangleAlertIcon } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { formatBusinessDate } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import type { StaleImport } from '../lib/figures'

/** Reminder, one line per bank account, that its imported transactions stop more than a week ago. */
defineProps<{ items: StaleImport[] }>()

const { t, locale } = useI18n()
</script>

<template>
  <div
    v-if="items.length > 0"
    role="status"
    class="rounded-md border border-border bg-card p-3 text-sm text-foreground"
  >
    <ul class="grid gap-2">
      <li v-for="item in items" :key="item.id" class="flex flex-wrap items-center gap-x-3 gap-y-1">
        <TriangleAlertIcon class="size-4 shrink-0 text-warning" aria-hidden="true" />
        <p class="min-w-0 flex-1">
          {{
            t('dashboard.staleImports.line', {
              account: item.name,
              date: formatBusinessDate(item.lastImportedDate, locale as Locale),
              days: item.days,
            })
          }}
        </p>
        <RouterLink
          :to="{ path: '/transactions/import', query: { bankAccountId: item.id } }"
          class="font-medium text-primary underline underline-offset-4"
        >
          {{ t('dashboard.staleImports.action') }}
        </RouterLink>
      </li>
    </ul>
  </div>
</template>
