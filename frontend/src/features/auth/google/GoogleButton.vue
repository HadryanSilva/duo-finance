<script setup lang="ts">
import { onMounted, useTemplateRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { useRouter } from 'vue-router'
import { useHttp } from '@/app/providers'
import { errorMessage } from '@/shared/i18n/error-codes'
import { useSession } from '@/shared/session/session-store'
import { googleLogin } from '../api/auth-api'
import { useGoogleSignup } from './google-signup-store'
import { loadGsi } from './load-gsi'

const props = defineProps<{ clientId: string; invitationToken?: string }>()
const emit = defineEmits<{ authenticated: [] }>()

const { t, locale } = useI18n()
const http = useHttp()
const session = useSession()
const router = useRouter()
const signup = useGoogleSignup()
const host = useTemplateRef<HTMLElement>('host')

async function onCredential(credential: string) {
  try {
    const resp = await googleLogin(http, credential)
    if (resp.status === 'AUTHENTICATED' && resp.accessToken && resp.user) {
      session.applyAuth({
        accessToken: resp.accessToken,
        expiresIn: resp.expiresIn ?? 0,
        user: resp.user,
      })
      emit('authenticated')
    } else if (resp.status === 'SIGNUP_REQUIRED' && resp.signupTicket) {
      signup.set({
        ticket: resp.signupTicket,
        email: resp.email ?? '',
        displayName: resp.displayName ?? '',
        credential,
        ...(props.invitationToken ? { invitationToken: props.invitationToken } : {}),
      })
      await router.push('/register/google')
    }
  } catch (e) {
    toast.error(errorMessage(e))
  }
}

onMounted(async () => {
  try {
    const gsi = await loadGsi()
    gsi.initialize({
      client_id: props.clientId,
      callback: (r) => void onCredential(r.credential),
      ux_mode: 'popup',
    })
    if (host.value) {
      gsi.renderButton(host.value, {
        type: 'standard',
        theme: 'outline',
        size: 'large',
        text: 'continue_with',
        width: 320,
        locale: locale.value,
      })
    }
  } catch {
    toast.error(t('errors.NETWORK_ERROR'))
  }
})
</script>

<template>
  <div
    ref="host"
    role="group"
    class="flex justify-center"
    :aria-label="t('auth.continueWithGoogle')"
  />
</template>
