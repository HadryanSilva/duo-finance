<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { z } from 'zod'
import { useHttp } from '@/app/providers'
import { detectLocale } from '@/shared/i18n'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import { Input } from '@/shared/ui/input'
import { Label } from '@/shared/ui/label'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import { joinWaitlist } from '../api/landing-api'

const { t } = useI18n()
const http = useHttp()

const schema = computed(() =>
  toTypedSchema(
    z.object({
      email: z.string().trim().min(1, t('validation.NotBlank')).email(t('validation.Email')),
      locale: z.enum(['pt-BR', 'en'], { message: t('validation.Invalid') }),
    }),
  ),
)
const { defineField, handleSubmit, errors, isSubmitting, setFieldError } = useForm({
  validationSchema: schema,
  initialValues: { email: '', locale: detectLocale() },
})
const [email, emailAttrs] = defineField('email')
const [locale, localeAttrs] = defineField('locale')

const done = ref(false)
const formError = ref<string | null>(null)

function showFieldError(field: string, message: string) {
  if (field === 'email' || field === 'locale') setFieldError(field, message)
  else formError.value = message
}

const submit = handleSubmit(async (v) => {
  formError.value = null
  try {
    await joinWaitlist(http, { email: v.email.trim(), locale: v.locale })
    done.value = true // identical outcome whether or not the email was already known
  } catch (e) {
    if (!applyFieldErrors(e, showFieldError)) formError.value = errorMessage(e)
  }
})
</script>

<template>
  <section class="rounded-lg border bg-card p-6" aria-labelledby="waitlist-title">
    <h2 id="waitlist-title" class="text-lg font-semibold text-foreground">
      {{ t('landing.waitlist.title') }}
    </h2>
    <div v-if="done" role="status" class="mt-3 grid gap-1">
      <p class="font-medium text-foreground">{{ t('landing.waitlist.successTitle') }}</p>
      <p class="text-sm text-muted-foreground">{{ t('landing.waitlist.successMessage') }}</p>
    </div>
    <form v-else class="mt-3 grid gap-4" novalidate @submit.prevent="submit">
      <p class="text-sm text-muted-foreground">{{ t('landing.waitlist.subtitle') }}</p>
      <p v-if="formError" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
        {{ formError }}
      </p>
      <div class="grid gap-2">
        <Label for="waitlist-email">{{ t('auth.email') }}</Label>
        <Input
          id="waitlist-email"
          v-model="email"
          v-bind="emailAttrs"
          type="email"
          autocomplete="email"
          :aria-invalid="errors.email ? 'true' : undefined"
          :aria-describedby="errors.email ? 'waitlist-email-error' : undefined"
        />
        <p v-if="errors.email" id="waitlist-email-error" class="text-sm text-destructive">
          {{ errors.email }}
        </p>
      </div>
      <div class="grid gap-2">
        <Label for="waitlist-locale">{{ t('auth.register.locale') }}</Label>
        <select
          id="waitlist-locale"
          v-model="locale"
          v-bind="localeAttrs"
          class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
        >
          <option value="pt-BR">Português (Brasil)</option>
          <option value="en">English</option>
        </select>
      </div>
      <Button type="submit" :disabled="isSubmitting">{{ t('landing.waitlist.submit') }}</Button>
    </form>
  </section>
</template>
