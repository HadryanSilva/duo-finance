<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Button } from '@/shared/ui/button'
import PageHeader from '@/shared/ui/PageHeader.vue'

const { t } = useI18n()
</script>

<template>
  <PageHeader :title="t('notFound.title')" :subtitle="t('notFound.message')" />
  <Button as-child variant="outline">
    <RouterLink to="/">{{ t('notFound.home') }}</RouterLink>
  </Button>
</template>
