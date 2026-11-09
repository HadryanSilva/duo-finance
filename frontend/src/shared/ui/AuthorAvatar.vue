<script setup lang="ts">
import { UserX } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { AuthorRef } from '@/shared/api/types'

/** Who created a record; `null` when that member has left the couple. */
const props = defineProps<{ author: AuthorRef | null }>()

const { t } = useI18n()

const label = computed(() => props.author?.displayName ?? t('author.formerMember'))
const initials = computed(() => {
  const words = (props.author?.displayName ?? '').trim().split(/\s+/).filter(Boolean)
  const first = words[0]?.[0] ?? ''
  const last = words.length > 1 ? (words.at(-1)?.[0] ?? '') : ''
  return (first + last).toUpperCase()
})
</script>

<template>
  <span
    role="img"
    :aria-label="label"
    :title="label"
    class="inline-flex size-7 shrink-0 items-center justify-center rounded-full text-xs font-medium"
    :class="author ? 'bg-primary/10 text-primary' : 'bg-muted text-muted-foreground'"
  >
    <span v-if="author" aria-hidden="true">{{ initials }}</span>
    <UserX v-else class="size-4" aria-hidden="true" />
  </span>
</template>
