<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSession } from '@/shared/session/session-store'
import Logo from '@/shared/ui/Logo.vue'
import { ADMIN, SIDEBAR_ITEMS } from './nav'

const { t } = useI18n()
const session = useSession()
const items = computed(() => (session.isAdmin ? [...SIDEBAR_ITEMS, ADMIN] : SIDEBAR_ITEMS))
</script>

<template>
  <aside
    data-testid="sidebar"
    class="sticky top-0 hidden h-screen w-60 shrink-0 flex-col gap-6 border-r bg-card px-3 py-6 md:flex"
  >
    <RouterLink to="/dashboard" class="flex items-center gap-2 px-3 text-lg font-semibold">
      <Logo size="sm" />
      <span>{{ t('app.name') }}</span>
    </RouterLink>
    <nav :aria-label="t('nav.main')">
      <ul class="flex flex-col gap-1">
        <li v-for="item in items" :key="item.to">
          <RouterLink
            :to="item.to"
            class="flex items-center gap-3 rounded-md px-3 py-2 text-sm font-medium text-muted-foreground transition-colors hover:bg-accent hover:text-accent-foreground"
            active-class="bg-accent text-foreground"
          >
            <component :is="item.icon" class="size-4" aria-hidden="true" />
            {{ t(item.label) }}
          </RouterLink>
        </li>
      </ul>
    </nav>
  </aside>
</template>
