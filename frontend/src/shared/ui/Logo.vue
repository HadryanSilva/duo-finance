<script setup lang="ts">
import { computed } from 'vue'

const props = withDefaults(defineProps<{ size?: 'sm' | 'md' | 'lg' }>(), { size: 'md' })

const sizeClass = computed(() => ({ sm: 'size-6', md: 'size-8', lg: 'size-12' })[props.size])
</script>

<template>
  <svg
    role="img"
    aria-label="Duo Finance"
    viewBox="0 0 32 32"
    fill="none"
    :class="[sizeClass, 'shrink-0']"
  >
    <circle cx="12" cy="16" r="9" class="stroke-brand" stroke-width="3" />
    <circle cx="20" cy="16" r="9" stroke="currentColor" stroke-width="3" />
  </svg>
</template>
