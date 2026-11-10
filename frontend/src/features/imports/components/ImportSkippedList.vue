<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { PreviewResponse } from '@/shared/api/types'
import { formatBusinessDate } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { Badge } from '@/shared/ui/badge'

/** Read-only list of the file's rows that will not be imported, with the reason. */
defineProps<{ rows: PreviewResponse['skipped']['rows'] }>()

const { t, locale } = useI18n()
</script>

<template>
  <ul v-if="rows.length" class="grid gap-2">
    <li
      v-for="row in rows"
      :key="row.lineNumber"
      class="flex flex-wrap items-center gap-3 rounded-lg border border-border bg-card p-3"
    >
      <div class="min-w-0 flex-1">
        <span class="truncate font-medium text-foreground">{{ row.description }}</span>
        <p class="text-sm text-muted-foreground">
          {{ formatBusinessDate(row.date, locale as Locale) }}
        </p>
      </div>
      <span class="text-sm tabular-nums text-muted-foreground">
        {{ row.amountRaw }} {{ row.currency }}
      </span>
      <Badge variant="outline">{{ t(`imports.skipReason.${row.reason}`) }}</Badge>
    </li>
  </ul>
  <p v-else class="text-sm text-muted-foreground">{{ t('imports.review.empty') }}</p>
</template>
