<script setup lang="ts">
import { ChevronDown } from '@lucide/vue'
import type { HTMLAttributes } from 'vue'
import { cn } from '@/shared/lib/utils'

/**
 * Native <select> with the app's look: own chevron with room on the right, 16px text below md so iOS
 * does not zoom on focus. Bind the value with v-model or :model-value; other attrs go to the select.
 */
defineOptions({ inheritAttrs: false })

const props = defineProps<{ class?: HTMLAttributes['class'] }>()
const model = defineModel<string>()
</script>

<template>
  <div :class="cn('relative', props.class)">
    <select
      v-model="model"
      class="h-9 w-full appearance-none rounded-md border border-input bg-background pr-9 pl-3 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive md:text-sm"
      v-bind="$attrs"
    >
      <slot />
    </select>
    <ChevronDown
      class="pointer-events-none absolute top-1/2 right-3 size-4 -translate-y-1/2 text-muted-foreground"
      aria-hidden="true"
    />
  </div>
</template>
