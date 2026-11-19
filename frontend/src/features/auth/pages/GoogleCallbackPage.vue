<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useHttp } from '@/app/providers'
import { errorMessage } from '@/shared/i18n/error-codes'
import { useSession } from '@/shared/session/session-store'
import PageHeader from '@/shared/ui/PageHeader.vue'
import { googleLogin } from '../api/auth-api'
import { useGoogleSignup } from '../google/google-signup-store'
import { CALLBACK_PATH, readGoogleCallback } from '../google/google-redirect'

const { t } = useI18n()
const http = useHttp()
const session = useSession()
const route = useRoute()
const router = useRouter()
const signup = useGoogleSignup()

const result = readGoogleCallback(route.hash)
// Keep the ID token out of the address bar and the browser history.
if (window.location.hash) window.history.replaceState(window.history.state, '', CALLBACK_PATH)

const error = ref<string | null>(null)

onMounted(async () => {
  if (result.kind === 'invalid') {
    error.value = t('auth.google.callbackInvalid')
    return
  }
  if (result.kind === 'cancelled') {
    await router.replace(result.cancelTo)
    return
  }
  try {
    const resp = await googleLogin(http, result.credential)
    if (resp.status === 'AUTHENTICATED' && resp.accessToken && resp.user) {
      session.applyAuth({
        accessToken: resp.accessToken,
        expiresIn: resp.expiresIn ?? 0,
        user: resp.user,
      })
      await router.replace(result.returnTo)
    } else if (resp.status === 'SIGNUP_REQUIRED' && resp.signupTicket) {
      signup.set({
        ticket: resp.signupTicket,
        email: resp.email ?? '',
        displayName: resp.displayName ?? '',
        credential: result.credential,
        ...(result.invitationToken ? { invitationToken: result.invitationToken } : {}),
      })
      await router.replace('/register/google')
    } else {
      error.value = t('errors.GOOGLE_TOKEN_INVALID')
    }
  } catch (e) {
    error.value = errorMessage(e)
  }
})
</script>

<template>
  <PageHeader :title="t('pages.googleCallback')" />
  <div v-if="error" class="grid gap-4">
    <p role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">{{ error }}</p>
    <RouterLink to="/login" class="text-sm text-primary hover:underline">
      {{ t('auth.backToLogin') }}
    </RouterLink>
  </div>
  <p v-else class="text-sm text-muted-foreground" aria-busy="true">
    {{ t('auth.google.signingIn') }}
  </p>
</template>
