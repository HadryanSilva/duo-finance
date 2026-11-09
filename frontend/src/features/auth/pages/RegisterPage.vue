<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useHttp } from '@/app/providers'
import type { AuthResponse } from '@/shared/api/types'
import { useRegistrationMode } from '@/shared/api/queries/public'
import { detectLocale } from '@/shared/i18n'
import { errorMessage } from '@/shared/i18n/error-codes'
import { TERMS_VERSION } from '@/shared/lib/legal'
import { markOnboarded } from '@/shared/session/onboarding'
import { useSession } from '@/shared/session/session-store'
import { Button } from '@/shared/ui/button'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import PageHeader from '@/shared/ui/PageHeader.vue'
import { register } from '../api/auth-api'
import PasswordField from '../components/PasswordField.vue'
import TextField from '@/shared/ui/TextField.vue'
import { registerSchema } from '../schemas'

const { t } = useI18n()
const http = useHttp()
const session = useSession()
const route = useRoute()
const router = useRouter()

const first = (v: unknown) => (typeof v === 'string' && v ? v : undefined)
const inviteToken = first(route.query.invite)

const mode = useRegistrationMode({ enabled: () => !inviteToken })
// An invitation replaces the code; in OPEN mode none is needed. If the mode can't be read, ask for it.
const modeKnown = computed(() => !!inviteToken || !mode.isPending.value)
const showCode = computed(() => !inviteToken && modeKnown.value && mode.data.value !== 'OPEN')

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
  if (inviteToken) {
    // The couple already has a name and there is no partner left to invite.
    markOnboarded(auth.user.id)
    await router.push('/dashboard')
  } else {
    await router.push('/onboarding')
  }
})
</script>

<template>
  <PageHeader :title="t('pages.register')" :subtitle="t('auth.register.subtitle')" />
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
      <select
        id="register-locale"
        v-model="locale"
        v-bind="localeAttrs"
        class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        <option value="pt-BR">Português (Brasil)</option>
        <option value="en">English</option>
      </select>
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
        <input
          id="register-terms"
          v-model="acceptTerms"
          v-bind="acceptTermsAttrs"
          type="checkbox"
          class="mt-0.5 size-4 accent-primary"
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
