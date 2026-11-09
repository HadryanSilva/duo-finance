<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useSubscription } from '@/shared/api/queries/subscription'
import { errorMessage } from '@/shared/i18n/error-codes'
import SettingsSection from './SettingsSection.vue'

const { t, locale } = useI18n()
const subscription = useSubscription()

const date = (instant: string) =>
  new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium' }).format(new Date(instant))
</script>

<template>
  <SettingsSection :title="t('settings.plan.title')">
    <p v-if="subscription.isError.value" role="alert" class="text-sm text-destructive">
      {{ errorMessage(subscription.error.value) }}
    </p>
    <dl v-else-if="subscription.data.value" class="grid gap-2 text-sm sm:grid-cols-2">
      <div>
        <dt class="text-muted-foreground">{{ t('settings.plan.plan') }}</dt>
        <dd class="font-medium text-foreground">
          {{ t(`settings.plan.names.${subscription.data.value.plan}`) }}
        </dd>
      </div>
      <div>
        <dt class="text-muted-foreground">{{ t('settings.plan.status') }}</dt>
        <dd class="font-medium text-foreground">
          {{ t(`settings.plan.statuses.${subscription.data.value.status}`) }}
        </dd>
      </div>
      <div v-if="subscription.data.value.currentPeriodEnd">
        <dt class="text-muted-foreground">{{ t('settings.plan.renews') }}</dt>
        <dd class="font-medium text-foreground">
          {{ date(subscription.data.value.currentPeriodEnd) }}
        </dd>
      </div>
    </dl>
  </SettingsSection>
</template>
