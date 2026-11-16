<script setup lang="ts">
import { computed } from 'vue'
import { useIsDesktop } from '@/shared/lib/breakpoints'
import { Sheet, SheetContent, SheetDescription, SheetHeader, SheetTitle } from '@/shared/ui/sheet'

const props = defineProps<{
  open: boolean
  title: string
  description?: string
  /**
   * Where focus goes when the panel closes. Needed when the opener may not hold focus at open
   * time: iOS Safari does not focus a tapped button, so focus would fall back to the page top.
   */
  returnFocus?: HTMLElement | null
}>()
const emit = defineEmits<{ 'update:open': [open: boolean] }>()

// Right-hand sheet from md (768px) up, bottom sheet on mobile.
const isDesktop = useIsDesktop()
const side = computed(() => (isDesktop.value ? 'right' : 'bottom'))

// On a phone, focusing the first field shows a focus ring mid-form and makes screen readers
// start there: focus the sheet itself, so reading starts at its title.
function onOpenAutoFocus(event: Event) {
  if (isDesktop.value) return
  event.preventDefault()
  ;(event.target as HTMLElement | null)?.focus()
}

function onCloseAutoFocus(event: Event) {
  if (!props.returnFocus) return
  event.preventDefault()
  props.returnFocus.focus()
}
</script>

<template>
  <Sheet :open="open" @update:open="emit('update:open', $event)">
    <SheetContent
      :side="side"
      class="max-h-dvh overflow-y-auto"
      @open-auto-focus="onOpenAutoFocus"
      @close-auto-focus="onCloseAutoFocus"
    >
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
