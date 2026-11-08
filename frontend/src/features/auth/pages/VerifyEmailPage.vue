<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { useHttp } from '@/app/providers'
import { useResendVerification } from '@/shared/api/queries/me'
import { errorMessage } from '@/shared/i18n/error-codes'
import { useSession } from '@/shared/session/session-store'
import { Button } from '@/shared/ui/button'
import PageHeader from '@/shared/ui/PageHeader.vue'
import { verifyEmail } from '../api/auth-api'

const { t } = useI18n()
const http = useHttp()
const session = useSession()
const route = useRoute()

const state = ref<'loading' | 'success' | 'error'>('loading')
const error = ref('')
const resent = ref(false)
const resendError = ref('')
const resendMutation = useResendVerification()

onMounted(async () => {
  const token = route.query.token
  if (typeof token !== 'string' || !token) {
    error.value = t('errors.TOKEN_INVALID')
    state.value = 'error'
    return
  }
  try {
    await verifyEmail(http, token)
    state.value = 'success'
  } catch (e) {
    error.value = errorMessage(e)
    state.value = 'error'
  }
})

async function resend() {
  resendError.value = ''
  resent.value = false
  try {
    await resendMutation.mutateAsync()
    resent.value = true
  } catch (e) {
    resendError.value = errorMessage(e)
  }
}
</script>

<template>
  <PageHeader :title="t('auth.verify.title')" />
  <p v-if="state === 'loading'" class="text-sm text-muted-foreground" aria-busy="true">
    {{ t('auth.verify.loading') }}
  </p>
  <div v-else-if="state === 'success'" role="status" class="grid gap-4">
    <p class="font-medium text-foreground">{{ t('auth.verify.success') }}</p>
    <p class="text-sm text-muted-foreground">{{ t('auth.verify.successMessage') }}</p>
    <Button as-child>
      <RouterLink :to="session.status === 'authenticated' ? '/dashboard' : '/login'">
        {{ session.status === 'authenticated' ? t('auth.verify.continue') : t('common.signIn') }}
      </RouterLink>
    </Button>
  </div>
  <div v-else class="grid gap-4">
    <p role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">{{ error }}</p>
    <template v-if="session.status === 'authenticated'">
      <Button variant="outline" :disabled="resendMutation.isPending.value" @click="resend">
        {{ t('auth.verify.resend') }}
      </Button>
      <p v-if="resent" role="status" class="text-sm text-muted-foreground">
        {{ t('auth.verify.resent') }}
      </p>
      <p v-if="resendError" role="alert" class="text-sm text-destructive">{{ resendError }}</p>
    </template>
  </div>
</template>
