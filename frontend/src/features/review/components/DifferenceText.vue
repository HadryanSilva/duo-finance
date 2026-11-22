<script setup lang="ts">
import { ArrowDown, ArrowUp } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import MoneyText from '@/shared/ui/MoneyText.vue'
import type { Tone } from '../lib/review'

/** `cents` is the month minus its average; `tone` says whether that is good news. */
const props = defineProps<{ cents: number; tone: Tone }>()

const { t } = useI18n()
const toneClass = computed(() =>
  props.tone === 'worse' ? 'text-expense' : props.tone === 'better' ? 'text-income' : '',
)
</script>

<template>
  <span v-if="tone === 'same'" class="text-muted-foreground">
    <span aria-hidden="true">—</span>
    <span class="sr-only">{{ t('review.noChange') }}</span>
  </span>
  <span v-else class="inline-flex items-center gap-1 font-medium" :class="toneClass">
    <MoneyText :cents="cents" signed />
    <ArrowUp v-if="cents > 0" class="size-3.5" aria-hidden="true" />
    <ArrowDown v-else class="size-3.5" aria-hidden="true" />
    <span class="sr-only">{{ cents > 0 ? t('review.more') : t('review.less') }}</span>
  </span>
</template>
