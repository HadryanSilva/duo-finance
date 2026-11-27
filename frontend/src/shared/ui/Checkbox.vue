<script setup lang="ts">
import { Check } from '@lucide/vue'
import type { HTMLAttributes } from 'vue'
import { cn } from '@/shared/lib/utils'

/** Native checkbox with the app's look. Bind with v-model; class goes on the wrapper, other attrs on the input. */
defineOptions({ inheritAttrs: false })

const props = defineProps<{ class?: HTMLAttributes['class'] }>()
const model = defineModel<boolean>()

function onChange(event: Event) {
  model.value = (event.target as HTMLInputElement).checked
}
</script>

<template>
  <span :class="cn('relative inline-grid size-4 shrink-0 place-items-center', props.class)">
    <input
      type="checkbox"
      class="peer size-4 cursor-pointer appearance-none rounded-sm border border-input bg-background transition-colors outline-none checked:border-primary checked:bg-primary enabled:hover:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 disabled:cursor-not-allowed disabled:opacity-50 aria-invalid:border-destructive"
      :checked="model"
      v-bind="$attrs"
      @change="onChange"
    />
    <Check
      class="pointer-events-none invisible absolute size-3 text-primary-foreground peer-checked:visible"
      :stroke-width="3"
      aria-hidden="true"
    />
  </span>
</template>
