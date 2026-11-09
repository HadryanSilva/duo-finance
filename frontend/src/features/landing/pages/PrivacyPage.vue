<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import PageHeader from '@/shared/ui/PageHeader.vue'

const { t } = useI18n()
</script>

<template>
  <PageHeader :title="t('legal.privacy.title')" />
  <div class="grid max-w-2xl gap-3 text-sm text-foreground">
    <p v-for="(paragraph, i) in t('legal.privacy.body').split('\n\n')" :key="i">{{ paragraph }}</p>
  </div>
</template>
