<script setup lang="ts">
import { Plus } from '@lucide/vue'
import { useI18n } from 'vue-i18n'
import { BUDGETS, DASHBOARD, SETTINGS, TRANSACTIONS } from './nav'

const { t } = useI18n()
const left = [DASHBOARD, TRANSACTIONS]
const right = [BUDGETS, SETTINGS]
const linkClass =
  'flex flex-col items-center gap-1 py-2 text-xs font-medium text-muted-foreground transition-colors hover:text-foreground'
</script>

<template>
  <nav
    data-testid="bottom-nav"
    :aria-label="t('nav.main')"
    class="fixed inset-x-0 bottom-0 z-40 border-t bg-card md:hidden"
  >
    <ul class="grid grid-cols-5 items-center">
      <li v-for="item in left" :key="item.to">
        <RouterLink :to="item.to" :class="linkClass" exact-active-class="text-brand">
          <component :is="item.icon" class="size-5" aria-hidden="true" />
          {{ t(item.label) }}
        </RouterLink>
      </li>
      <li class="flex justify-center">
        <RouterLink
          :to="{ path: '/transactions', query: { new: '1' } }"
          :aria-label="t('nav.newTransaction')"
          class="flex size-12 items-center justify-center rounded-full bg-brand text-brand-foreground shadow-sm transition-opacity hover:opacity-90"
        >
          <Plus class="size-6" aria-hidden="true" />
        </RouterLink>
      </li>
      <li v-for="item in right" :key="item.to">
        <RouterLink :to="item.to" :class="linkClass" exact-active-class="text-brand">
          <component :is="item.icon" class="size-5" aria-hidden="true" />
          {{ t(item.label) }}
        </RouterLink>
      </li>
    </ul>
  </nav>
</template>
