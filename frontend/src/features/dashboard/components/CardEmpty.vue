<script setup lang="ts">
import { RouterLink } from 'vue-router'
import { Button } from '@/shared/ui/button'

defineProps<{ title: string; description: string; cta: string; to: string }>()
</script>

<template>
  <div class="flex flex-col items-start gap-3">
    <div>
      <p class="font-medium text-foreground">{{ title }}</p>
      <p class="mt-1 text-sm text-muted-foreground">{{ description }}</p>
    </div>
    <Button as-child size="sm" variant="outline">
      <RouterLink :to="to">{{ cta }}</RouterLink>
    </Button>
  </div>
</template>
