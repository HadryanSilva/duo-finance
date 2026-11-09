<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useHttp } from '@/app/providers'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import PageHeader from '@/shared/ui/PageHeader.vue'
import { resetPassword } from '../api/auth-api'
import PasswordField from '../components/PasswordField.vue'
import { resetPasswordSchema } from '../schemas'

const { t } = useI18n()
const http = useHttp()
const route = useRoute()
const router = useRouter()

const token = typeof route.query.token === 'string' ? route.query.token : ''

const { defineField, handleSubmit, errors, isSubmitting, setFieldError } = useForm({
  validationSchema: computed(() => toTypedSchema(resetPasswordSchema(t))),
})
const [newPassword, newPasswordAttrs] = defineField('newPassword')

const formError = ref<string | null>(token ? null : t('errors.TOKEN_INVALID'))

function showFieldError(field: string, message: string) {
  if (field === 'newPassword') setFieldError('newPassword', message)
  else formError.value = message
}

const submit = handleSubmit(async (values) => {
  formError.value = null
  try {
    await resetPassword(http, token, values.newPassword)
  } catch (e) {
    if (!applyFieldErrors(e, showFieldError)) formError.value = errorMessage(e)
    return
  }
  await router.push({ path: '/login', query: { reset: '1' } })
})
</script>

<template>
  <PageHeader :title="t('pages.resetPassword')" :subtitle="t('auth.reset.subtitle')" />
  <form class="grid gap-4" novalidate @submit.prevent="submit">
    <p v-if="formError" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ formError }}
    </p>
    <PasswordField
      v-model="newPassword"
      v-bind="newPasswordAttrs"
      :label="t('auth.newPassword')"
      :error="errors.newPassword"
      :hint="t('auth.passwordHint')"
      autocomplete="new-password"
    />
    <Button type="submit" :disabled="isSubmitting || !token">{{ t('auth.reset.submit') }}</Button>
  </form>
  <p class="mt-6 text-center text-sm">
    <RouterLink :to="token ? '/login' : '/forgot-password'" class="text-primary hover:underline">
      {{ token ? t('auth.backToLogin') : t('auth.reset.requestNew') }}
    </RouterLink>
  </p>
</template>
