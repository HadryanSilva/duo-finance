<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useQuery } from '@tanstack/vue-query'
import { useForm } from 'vee-validate'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useHttp } from '@/app/providers'
import { useAuthConfig } from '@/shared/api/queries/public'
import { isApiError } from '@/shared/api/problem'
import type { AuthResponse } from '@/shared/api/types'
import { detectLocale } from '@/shared/i18n'
import { errorMessage } from '@/shared/i18n/error-codes'
import { TERMS_VERSION } from '@/shared/lib/legal'
import { useSession } from '@/shared/session/session-store'
import { Button } from '@/shared/ui/button'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import PageHeader from '@/shared/ui/PageHeader.vue'
import TextField from '@/shared/ui/TextField.vue'
import { googleComplete, googleLogin, invitationPreview } from '../api/auth-api'
import GoogleButton from '../google/GoogleButton.vue'
import { useGoogleSignup } from '../google/google-signup-store'
import { googleSignupSchema } from '../schemas'

const { t } = useI18n()
const http = useHttp()
const session = useSession()
const router = useRouter()
const store = useGoogleSignup()

// Without a pending Google sign-up there is nothing to complete (e.g. a reload cleared the memory).
if (!store.signup) void router.replace('/login')

const config = useAuthConfig()
const googleClientId = computed(() => config.data.value?.googleClientId ?? null)

const expired = ref(false)
const dropInvite = ref(false)
const invitationToken = computed(() =>
  dropInvite.value ? undefined : store.signup?.invitationToken,
)

const preview = useQuery({
  queryKey: ['invitation-preview', invitationToken],
  queryFn: () => invitationPreview(http, invitationToken.value ?? ''),
  enabled: computed(() => !!invitationToken.value),
  retry: false,
})
// An invitation addressed to another e-mail cannot be used here: warn and fall back to the code.
const inviteOtherEmail = ref<string | null>(null)
watch(
  () => preview.data.value,
  (p) => {
    if (p && p.email.toLowerCase() !== store.signup?.email.toLowerCase()) {
      inviteOtherEmail.value = p.email
      dropInvite.value = true
    }
  },
)
watch(
  () => preview.isError.value,
  (failed) => {
    if (failed) dropInvite.value = true
  },
)
const invitePending = computed(() => !!invitationToken.value && preview.isPending.value)

// An invitation for this e-mail replaces the code; in OPEN mode none is needed.
const showCode = computed(
  () => !invitationToken.value && !config.isPending.value && config.data.value?.mode !== 'OPEN',
)

const { defineField, handleSubmit, errors, isSubmitting, setFieldError, setValues } = useForm({
  validationSchema: computed(() => toTypedSchema(googleSignupSchema(t, showCode.value))),
  initialValues: {
    displayName: store.signup?.displayName ?? '',
    acceptTerms: false,
    accessCode: '',
  },
})
const [displayName, displayNameAttrs] = defineField('displayName')
const [acceptTerms, acceptTermsAttrs] = defineField('acceptTerms')
const [accessCode, accessCodeAttrs] = defineField('accessCode')

// A new Google sign-in after the ticket expired starts the form over.
watch(
  () => store.signup,
  (s) => {
    if (!s) return
    expired.value = false
    dropInvite.value = false
    inviteOtherEmail.value = null
    setValues({ displayName: s.displayName, acceptTerms: false, accessCode: '' })
  },
)

const formError = ref<string | null>(null)
const FIELDS = ['displayName', 'acceptTerms', 'accessCode'] as const
type Field = (typeof FIELDS)[number]
const isField = (f: string): f is Field => (FIELDS as readonly string[]).includes(f)
function showFieldError(field: string, message: string) {
  if (isField(field)) setFieldError(field, message)
  else formError.value = message
}

async function finish(auth: AuthResponse, invited: boolean) {
  session.applyAuth(auth)
  store.clear()
  // The server marks an invited partner as onboarded: the couple already has a name.
  await router.push(invited ? '/dashboard' : '/onboarding')
}

const submit = handleSubmit(async (v) => {
  const pending = store.signup
  if (!pending) return
  formError.value = null
  const invited = !!invitationToken.value
  let auth: AuthResponse
  try {
    auth = await googleComplete(http, {
      signupTicket: pending.ticket,
      displayName: v.displayName.trim(),
      locale: detectLocale(),
      acceptTerms: true,
      termsVersion: TERMS_VERSION,
      ...(invitationToken.value
        ? { invitationToken: invitationToken.value }
        : showCode.value && v.accessCode
          ? { accessCode: v.accessCode }
          : {}),
    })
  } catch (e) {
    if (isApiError(e) && e.code === 'GOOGLE_SIGNUP_TICKET_INVALID') {
      store.clear()
      expired.value = true
    } else if (isApiError(e) && e.code === 'EMAIL_ALREADY_REGISTERED') {
      await retryLogin(pending.credential)
    } else if (!applyFieldErrors(e, showFieldError)) {
      formError.value = errorMessage(e)
    }
    return
  }
  await finish(auth, invited)
})

// The e-mail got registered meanwhile: signing in with the same Google credential links it.
async function retryLogin(credential: string) {
  try {
    const resp = await googleLogin(http, credential)
    if (resp.status === 'AUTHENTICATED' && resp.accessToken && resp.user) {
      session.applyAuth({
        accessToken: resp.accessToken,
        expiresIn: resp.expiresIn ?? 0,
        user: resp.user,
      })
      store.clear()
      await router.push('/dashboard')
      return
    }
  } catch {
    // falls through to the login page
  }
  store.clear()
  await router.push('/login')
}
</script>

<template>
  <PageHeader :title="t('pages.googleSignup')" :subtitle="t('auth.google.subtitle')" />
  <div v-if="expired" class="grid gap-4">
    <p role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ t('auth.google.ticketExpired') }}
    </p>
    <GoogleButton v-if="googleClientId" :client-id="googleClientId" />
  </div>
  <form v-else-if="store.signup" class="grid gap-4" novalidate @submit.prevent="submit">
    <p v-if="formError" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ formError }}
    </p>
    <p
      v-if="inviteOtherEmail"
      role="status"
      class="rounded-md bg-muted p-3 text-sm text-muted-foreground"
    >
      {{ t('auth.google.inviteOtherEmail', { email: inviteOtherEmail }) }}
    </p>
    <TextField :model-value="store.signup.email" :label="t('auth.email')" type="email" readonly />
    <TextField
      v-model="displayName"
      v-bind="displayNameAttrs"
      :label="t('auth.register.displayName')"
      :error="errors.displayName"
      autocomplete="name"
    />
    <TextField
      v-if="showCode"
      v-model="accessCode"
      v-bind="accessCodeAttrs"
      :label="t('auth.register.accessCode')"
      :error="errors.accessCode"
      :hint="t('auth.register.accessCodeHint')"
      autocomplete="off"
    />
    <div class="grid gap-2">
      <div class="flex items-start gap-2">
        <input
          id="google-terms"
          v-model="acceptTerms"
          v-bind="acceptTermsAttrs"
          type="checkbox"
          class="mt-0.5 size-4 accent-primary"
          :aria-invalid="errors.acceptTerms ? 'true' : undefined"
          :aria-describedby="errors.acceptTerms ? 'google-terms-error' : undefined"
        />
        <label for="google-terms" class="text-sm text-muted-foreground">
          <i18n-t keypath="auth.register.acceptTerms" scope="global">
            <template #terms>
              <RouterLink to="/terms" class="text-primary hover:underline">
                {{ t('auth.register.terms') }}
              </RouterLink>
            </template>
            <template #privacy>
              <RouterLink to="/privacy" class="text-primary hover:underline">
                {{ t('auth.register.privacy') }}
              </RouterLink>
            </template>
          </i18n-t>
        </label>
      </div>
      <p v-if="errors.acceptTerms" id="google-terms-error" class="text-sm text-destructive">
        {{ errors.acceptTerms }}
      </p>
    </div>
    <Button type="submit" :disabled="isSubmitting || invitePending || config.isPending.value">
      {{ t('auth.google.submit') }}
    </Button>
  </form>
</template>
