<script setup lang="ts">
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useMe } from '@/shared/api/queries/me'
import { useSession } from '@/shared/session/session-store'
import PageHeader from '@/shared/ui/PageHeader.vue'
import CoupleSection from '../components/CoupleSection.vue'
import DangerZoneSection from '../components/DangerZoneSection.vue'
import PasswordSection from '../components/PasswordSection.vue'
import PlanSection from '../components/PlanSection.vue'
import PreferencesSection from '../components/PreferencesSection.vue'
import ProfileSection from '../components/ProfileSection.vue'

const { t } = useI18n()
const session = useSession()
const me = useMe()

// Fresh from the server (the email may have been verified in another tab); the session's copy until then.
const emailVerified = computed(
  () => me.data.value?.emailVerified ?? session.user?.emailVerified ?? false,
)
</script>

<template>
  <PageHeader :title="t('nav.settings')" />
  <div class="grid max-w-2xl gap-6">
    <ProfileSection :email-verified="emailVerified" />
    <PasswordSection />
    <PreferencesSection />
    <CoupleSection :email-verified="emailVerified" />
    <PlanSection />
    <DangerZoneSection />
  </div>
</template>
