<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import PageHeader from '@/shared/ui/PageHeader.vue'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/ui/tabs'
import AccessCodesTab from '../components/AccessCodesTab.vue'
import WaitlistTab from '../components/WaitlistTab.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

// The tab lives in the URL; each tab keeps its own filters, so switching drops the others.
const tab = computed(() => (route.query.tab === 'codes' ? 'codes' : 'waitlist'))
const setTab = (value: string | number) =>
  router.replace({ query: value === 'codes' ? { tab: 'codes' } : {} })
</script>

<template>
  <PageHeader :title="t('admin.title')" />
  <Tabs :model-value="tab" @update:model-value="setTab">
    <TabsList>
      <TabsTrigger value="waitlist">{{ t('admin.tabs.waitlist') }}</TabsTrigger>
      <TabsTrigger value="codes">{{ t('admin.tabs.codes') }}</TabsTrigger>
    </TabsList>
    <TabsContent value="waitlist" class="mt-4"><WaitlistTab /></TabsContent>
    <TabsContent value="codes" class="mt-4"><AccessCodesTab /></TabsContent>
  </Tabs>
</template>
