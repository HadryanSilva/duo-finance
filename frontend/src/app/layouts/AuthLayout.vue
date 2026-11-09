<script setup lang="ts">
import { Card, CardContent } from '@/shared/ui/card'
import Logo from '@/shared/ui/Logo.vue'
</script>

<template>
  <div
    class="flex min-h-screen flex-col items-center justify-center gap-6 bg-background px-4 py-10"
  >
    <RouterLink to="/">
      <Logo size="lg" />
    </RouterLink>
    <Card class="w-full max-w-md">
      <CardContent>
        <slot />
      </CardContent>
    </Card>
  </div>
</template>
