<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import PageHeader from '@/shared/ui/PageHeader.vue'

const { t } = useI18n()
</script>

<template>
  <PageHeader :title="t('pages.onboarding')" />
</template>
