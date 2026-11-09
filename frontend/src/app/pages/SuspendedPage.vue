<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useSession } from '@/shared/session/session-store'
import { Button } from '@/shared/ui/button'
import PageHeader from '@/shared/ui/PageHeader.vue'

const { t } = useI18n()
const session = useSession()
</script>

<template>
  <PageHeader :title="t('suspended.title')" :subtitle="t('suspended.message')" />
  <div class="flex flex-col gap-2 sm:flex-row">
    <Button as-child>
      <RouterLink to="/settings">{{ t('suspended.settings') }}</RouterLink>
    </Button>
    <Button variant="outline" @click="session.logout()">{{ t('common.logout') }}</Button>
  </div>
</template>
