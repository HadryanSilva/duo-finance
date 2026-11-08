<script setup lang="ts">
import Logo from '@/shared/ui/Logo.vue'
import AppSidebar from '../components/AppSidebar.vue'
import BottomNav from '../components/BottomNav.vue'
import ThemeToggle from '../components/ThemeToggle.vue'
import UserMenu from '../components/UserMenu.vue'
</script>

<template>
  <div class="min-h-screen bg-background md:flex">
    <AppSidebar />
    <div class="flex min-w-0 flex-1 flex-col">
      <header
        class="flex items-center justify-between gap-4 border-b bg-card px-4 py-3 md:justify-end md:px-8"
      >
        <RouterLink to="/dashboard" class="md:hidden">
          <Logo size="sm" />
        </RouterLink>
        <div class="flex items-center gap-2">
          <ThemeToggle />
          <UserMenu />
        </div>
      </header>
      <main class="mx-auto w-full max-w-6xl flex-1 px-4 pt-6 pb-24 md:px-8 md:pb-8">
        <slot />
      </main>
    </div>
    <BottomNav />
  </div>
</template>
