<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import AppLayout from './layouts/AppLayout.vue'
import AuthLayout from './layouts/AuthLayout.vue'
import PublicLayout from './layouts/PublicLayout.vue'

const route = useRoute()
const LAYOUTS = { public: PublicLayout, auth: AuthLayout, app: AppLayout }
const layout = computed(() => LAYOUTS[route.meta.layout ?? 'public'])
</script>

<template>
  <!-- Nothing renders until the first navigation (and the session restore) settles. -->
  <component :is="layout" v-if="route.matched.length">
    <RouterView />
  </component>
</template>
