<script setup lang="ts">
import { useId } from 'vue'

defineProps<{ title: string; description?: string }>()

const id = useId()
</script>

<template>
  <section :aria-labelledby="id" class="grid gap-4 rounded-lg border border-border bg-card p-6">
    <header>
      <h2 :id="id" class="text-lg font-semibold text-foreground">{{ title }}</h2>
      <p v-if="description" class="mt-1 text-sm text-muted-foreground">{{ description }}</p>
    </header>
    <slot />
  </section>
</template>
