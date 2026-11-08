<script setup lang="ts">
defineProps<{ title: string; description?: string }>()
</script>

<template>
  <div
    class="flex flex-col items-center gap-3 rounded-lg border border-dashed border-border bg-card px-6 py-12 text-center"
  >
    <div v-if="$slots.icon" class="text-muted-foreground"><slot name="icon" /></div>
    <h2 class="text-lg font-semibold text-foreground">{{ title }}</h2>
    <p v-if="description" class="max-w-md text-sm text-muted-foreground">{{ description }}</p>
    <div v-if="$slots.default" class="mt-2"><slot /></div>
  </div>
</template>
