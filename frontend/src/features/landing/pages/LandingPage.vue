<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRegistrationMode } from '@/shared/api/queries/public'
import { Button } from '@/shared/ui/button'
import FeatureList from '../components/FeatureList.vue'
import WaitlistForm from '../components/WaitlistForm.vue'

const { t } = useI18n()
const mode = useRegistrationMode()
</script>

<template>
  <div class="grid gap-10 py-6 md:py-10">
    <section class="grid max-w-2xl gap-4">
      <h1 class="text-3xl font-semibold tracking-tight text-foreground md:text-4xl">
        {{ t('landing.title') }}
      </h1>
      <p class="text-lg text-muted-foreground">{{ t('landing.subtitle') }}</p>
      <div class="flex flex-wrap gap-2">
        <Button as-child>
          <RouterLink to="/login">{{ t('common.signIn') }}</RouterLink>
        </Button>
        <Button v-if="mode.data.value === 'OPEN'" as-child variant="outline">
          <RouterLink to="/register">{{ t('landing.createAccount') }}</RouterLink>
        </Button>
      </div>
    </section>
    <FeatureList />
    <div class="max-w-xl">
      <WaitlistForm />
    </div>
    <footer class="border-t pt-4">
      <nav :aria-label="t('landing.footer')" class="flex gap-4 text-sm text-muted-foreground">
        <RouterLink to="/privacy" class="hover:text-foreground hover:underline">
          {{ t('pages.privacy') }}
        </RouterLink>
        <RouterLink to="/terms" class="hover:text-foreground hover:underline">
          {{ t('pages.terms') }}
        </RouterLink>
      </nav>
    </footer>
  </div>
</template>
