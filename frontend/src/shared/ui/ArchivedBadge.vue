<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { Badge } from '@/shared/ui/badge'

const { t } = useI18n()
</script>

<template>
  <Badge variant="secondary">{{ t('common.archived') }}</Badge>
</template>
