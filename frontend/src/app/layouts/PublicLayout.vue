<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Button } from '@/shared/ui/button'
import Logo from '@/shared/ui/Logo.vue'

const { t } = useI18n()
</script>

<template>
  <div class="flex min-h-screen flex-col bg-background">
    <header class="border-b bg-card">
      <div
        class="mx-auto flex w-full max-w-6xl items-center justify-between gap-4 px-4 py-3 md:px-8"
      >
        <RouterLink to="/" class="flex items-center gap-2 text-lg font-semibold">
          <Logo size="sm" />
          <span>{{ t('app.name') }}</span>
        </RouterLink>
        <Button as-child variant="outline" size="sm">
          <RouterLink to="/login">{{ t('common.signIn') }}</RouterLink>
        </Button>
      </div>
    </header>
    <main class="mx-auto w-full max-w-6xl flex-1 px-4 py-6 md:px-8">
      <slot />
    </main>
  </div>
</template>
