<script setup lang="ts">
import Checkbox from '@/shared/ui/Checkbox.vue'
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useHttp } from '@/app/providers'
import type { AuthResponse } from '@/shared/api/types'
import { useAuthConfig } from '@/shared/api/queries/public'
import { detectLocale } from '@/shared/i18n'
import { errorMessage } from '@/shared/i18n/error-codes'
import { TERMS_VERSION } from '@/shared/lib/legal'
import { useSession } from '@/shared/session/session-store'
import { Button } from '@/shared/ui/button'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import PageHeader from '@/shared/ui/PageHeader.vue'
import { register } from '../api/auth-api'
import PasswordField from '../components/PasswordField.vue'
import TextField from '@/shared/ui/TextField.vue'
import { registerSchema } from '../schemas'
import GoogleButton from '../google/GoogleButton.vue'
import NativeSelect from '@/shared/ui/NativeSelect.vue'

const { t } = useI18n()
const http = useHttp()
const session = useSession()
const route = useRoute()
const router = useRouter()

const first = (v: unknown) => (typeof v === 'string' && v ? v : undefined)
const inviteToken = first(route.query.invite)

const config = useAuthConfig()
const googleClientId = computed(() => config.data.value?.googleClientId ?? null)
// An invitation replaces the code; in OPEN mode none is needed. If the mode can't be read, ask for it.
const modeKnown = computed(() => !!inviteToken || !config.isPending.value)
const showCode = computed(
  () => !inviteToken && modeKnown.value && config.data.value?.mode !== 'OPEN',
)

const { defineField, handleSubmit, errors, isSubmitting, setFieldError } = useForm({
  validationSchema: computed(() => toTypedSchema(registerSchema(t, showCode.value))),
  initialValues: {
    email: first(route.query.email) ?? '',
    password: '',
    displayName: '',
    locale: detectLocale(),
    acceptTerms: false,
    accessCode: first(route.query.code) ?? '',
  },
})
const [email, emailAttrs] = defineField('email')
const [password, passwordAttrs] = defineField('password')
const [displayName, displayNameAttrs] = defineField('displayName')
const [locale, localeAttrs] = defineField('locale')
const [acceptTerms, acceptTermsAttrs] = defineField('acceptTerms')
const [accessCode, accessCodeAttrs] = defineField('accessCode')

const formError = ref<string | null>(null)

const FIELDS = ['email', 'password', 'displayName', 'locale', 'acceptTerms', 'accessCode'] as const
type Field = (typeof FIELDS)[number]
const isField = (f: string): f is Field => (FIELDS as readonly string[]).includes(f)

function showFieldError(field: string, message: string) {
  if (isField(field)) setFieldError(field, message)
  else formError.value = message
}

// A Google account that already exists signs in; a new one is sent to /register/google. An existing account that
// came with an invitation still has to accept it, on the invite page.
const googleReturnTo = inviteToken ? `/invite/${encodeURIComponent(inviteToken)}` : '/dashboard'

const submit = handleSubmit(async (v) => {
  formError.value = null
  let auth: AuthResponse
  try {
    auth = await register(http, {
      email: v.email,
      password: v.password,
      displayName: v.displayName.trim(),
      locale: v.locale,
      acceptTerms: true,
      termsVersion: TERMS_VERSION,
      ...(inviteToken
        ? { invitationToken: inviteToken }
        : showCode.value && v.accessCode
          ? { accessCode: v.accessCode }
          : {}),
    })
  } catch (e) {
    if (!applyFieldErrors(e, showFieldError)) formError.value = errorMessage(e)
    return
  }
  session.applyAuth(auth)
  // The server marks an invited partner as onboarded: the couple already has a name.
  if (inviteToken) {
    await router.push('/dashboard')
  } else {
    await router.push('/onboarding')
  }
})
</script>

<template>
  <PageHeader :title="t('pages.register')" :subtitle="t('auth.register.subtitle')" />
  <div v-if="googleClientId" class="mb-6 grid gap-4">
    <GoogleButton
      :client-id="googleClientId"
      :invitation-token="inviteToken"
      :return-to="googleReturnTo"
    />
    <div class="flex items-center gap-3 text-xs text-muted-foreground" aria-hidden="true">
      <span class="h-px flex-1 bg-border" />
      {{ t('auth.or') }}
      <span class="h-px flex-1 bg-border" />
    </div>
  </div>
  <form class="grid gap-4" novalidate @submit.prevent="submit">
    <p v-if="formError" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ formError }}
    </p>
    <TextField
      v-model="email"
      v-bind="emailAttrs"
      :label="t('auth.email')"
      :error="errors.email"
      type="email"
      autocomplete="email"
    />
    <PasswordField
      v-model="password"
      v-bind="passwordAttrs"
      :label="t('auth.password')"
      :error="errors.password"
      :hint="t('auth.passwordHint')"
      autocomplete="new-password"
    />
    <TextField
      v-model="displayName"
      v-bind="displayNameAttrs"
      :label="t('auth.register.displayName')"
      :error="errors.displayName"
      autocomplete="name"
    />
    <div class="grid gap-2">
      <label for="register-locale" class="text-sm font-medium">
        {{ t('auth.register.locale') }}
      </label>
      <NativeSelect id="register-locale" v-model="locale" v-bind="localeAttrs">
        <option value="pt-BR">Português (Brasil)</option>
        <option value="en">English</option>
      </NativeSelect>
    </div>
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
        <Checkbox
          id="register-terms"
          v-model="acceptTerms"
          v-bind="acceptTermsAttrs"
          class="mt-0.5"
          :aria-invalid="errors.acceptTerms ? 'true' : undefined"
          :aria-describedby="errors.acceptTerms ? 'register-terms-error' : undefined"
        />
        <label for="register-terms" class="text-sm text-muted-foreground">
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
      <p v-if="errors.acceptTerms" id="register-terms-error" class="text-sm text-destructive">
        {{ errors.acceptTerms }}
      </p>
    </div>
    <Button type="submit" :disabled="isSubmitting || !modeKnown">
      {{ t('auth.register.submit') }}
    </Button>
  </form>
  <p class="mt-6 text-center text-sm text-muted-foreground">
    {{ t('auth.register.haveAccount') }}
    <RouterLink to="/login" class="text-primary hover:underline">
      {{ t('common.signIn') }}
    </RouterLink>
  </p>
</template>
