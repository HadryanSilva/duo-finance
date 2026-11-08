<script setup lang="ts">
import { useMediaQuery } from '@vueuse/core'
import { computed } from 'vue'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/shared/ui/sheet'

defineProps<{ open: boolean; title: string; description?: string }>()
const emit = defineEmits<{ 'update:open': [open: boolean] }>()

// Right-hand sheet from md (768px) up, bottom sheet on mobile.
const isDesktop = useMediaQuery('(min-width: 48rem)')
const side = computed(() => (isDesktop.value ? 'right' : 'bottom'))
</script>

<template>
  <Sheet :open="open" @update:open="emit('update:open', $event)">
    <SheetContent :side="side" class="max-h-dvh overflow-y-auto">
      <SheetHeader>
        <SheetTitle>{{ title }}</SheetTitle>
        <SheetDescription :class="description ? undefined : 'sr-only'">
          {{ description ?? title }}
        </SheetDescription>
      </SheetHeader>
      <div class="px-4 pb-6">
        <slot />
      </div>
    </SheetContent>
  </Sheet>
</template>
