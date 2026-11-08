<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useHttp } from '@/app/providers'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import PageHeader from '@/shared/ui/PageHeader.vue'
import { forgotPassword } from '../api/auth-api'
import TextField from '../components/TextField.vue'
import { forgotPasswordSchema } from '../schemas'

const { t } = useI18n()
const http = useHttp()

const { defineField, handleSubmit, errors, isSubmitting } = useForm({
  validationSchema: computed(() => toTypedSchema(forgotPasswordSchema(t))),
})
const [email, emailAttrs] = defineField('email')

const sent = ref(false)
const formError = ref<string | null>(null)

const submit = handleSubmit(async (values) => {
  formError.value = null
  try {
    await forgotPassword(http, values.email)
    sent.value = true // the same message whether or not the email has an account
  } catch (e) {
    formError.value = errorMessage(e)
  }
})
</script>

<template>
  <PageHeader :title="t('pages.forgotPassword')" :subtitle="t('auth.forgot.subtitle')" />
  <div v-if="sent" role="status" class="grid gap-2">
    <p class="font-medium text-foreground">{{ t('auth.forgot.sentTitle') }}</p>
    <p class="text-sm text-muted-foreground">{{ t('auth.forgot.sentMessage') }}</p>
  </div>
  <form v-else class="grid gap-4" novalidate @submit.prevent="submit">
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
    <Button type="submit" :disabled="isSubmitting">{{ t('auth.forgot.submit') }}</Button>
  </form>
  <p class="mt-6 text-center text-sm">
    <RouterLink to="/login" class="text-primary hover:underline">
      {{ t('auth.backToLogin') }}
    </RouterLink>
  </p>
</template>
