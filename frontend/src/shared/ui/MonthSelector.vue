<script setup lang="ts">
import { ChevronLeft, ChevronRight } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { addMonths, formatMonth } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { Button } from '@/shared/ui/button'

/** Month as `YYYY-MM`. */
const model = defineModel<string>({ required: true })

const { t, locale } = useI18n()
const label = computed(() => formatMonth(model.value, locale.value as Locale))
</script>

<template>
  <div class="flex items-center gap-2" role="group" :aria-label="t('monthSelector.label')">
    <Button
      variant="outline"
      size="icon"
      :aria-label="t('monthSelector.previous')"
      @click="model = addMonths(model, -1)"
    >
      <ChevronLeft class="size-4" aria-hidden="true" />
    </Button>
    <span class="min-w-40 text-center font-medium capitalize text-foreground" aria-live="polite">{{
      label
    }}</span>
    <Button
      variant="outline"
      size="icon"
      :aria-label="t('monthSelector.next')"
      @click="model = addMonths(model, 1)"
    >
      <ChevronRight class="size-4" aria-hidden="true" />
    </Button>
  </div>
</template>
