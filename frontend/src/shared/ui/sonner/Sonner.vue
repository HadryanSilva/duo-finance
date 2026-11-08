<script lang="ts" setup>
import type { ToasterProps } from 'vue-sonner'
import {
  CircleCheckIcon,
  InfoIcon,
  Loader2Icon,
  OctagonXIcon,
  TriangleAlertIcon,
  XIcon,
} from '@lucide/vue'
import { cn } from '@/shared/lib/utils'
import { ref } from 'vue'
import { useMutationObserver } from '@vueuse/core'
import { Toaster as Sonner } from 'vue-sonner'

const props = defineProps<ToasterProps>()

// The app's dark mode is the `.dark` class on <html>; keep the toaster in sync.
const root = document.documentElement
const theme = ref<'light' | 'dark'>(root.classList.contains('dark') ? 'dark' : 'light')
useMutationObserver(
  root,
  () => {
    theme.value = root.classList.contains('dark') ? 'dark' : 'light'
  },
  { attributes: true, attributeFilter: ['class'] },
)
</script>

<template>
  <Sonner
    :class="cn('toaster group', props.class)"
    :style="{
      '--normal-bg': 'var(--color-popover)',
      '--normal-text': 'var(--color-popover-foreground)',
      '--normal-border': 'var(--color-border)',
      '--border-radius': 'var(--radius-lg)',
    }"
    v-bind="props"
    :theme="theme"
  >
    <template #success-icon>
      <CircleCheckIcon class="size-4" />
    </template>
    <template #info-icon>
      <InfoIcon class="size-4" />
    </template>
    <template #warning-icon>
      <TriangleAlertIcon class="size-4" />
    </template>
    <template #error-icon>
      <OctagonXIcon class="size-4" />
    </template>
    <template #loading-icon>
      <div>
        <Loader2Icon class="size-4 animate-spin" />
      </div>
    </template>
    <template #close-icon>
      <XIcon class="size-4" />
    </template>
  </Sonner>
</template>
