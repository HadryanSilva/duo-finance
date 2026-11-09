<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { z } from 'zod'
import { isApiError } from '@/shared/api/problem'
import { useChangePassword } from '@/shared/api/queries/me'
import { errorMessage } from '@/shared/i18n/error-codes'
import { useSession } from '@/shared/session/session-store'
import { Button } from '@/shared/ui/button'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import TextField from '@/shared/ui/TextField.vue'
import SettingsSection from './SettingsSection.vue'

const { t } = useI18n()
const session = useSession()
const change = useChangePassword()

const schema = computed(() =>
  toTypedSchema(
    z.object({
      currentPassword: z.string().min(1, t('validation.NotBlank')),
      newPassword: z.string().min(8, t('validation.Size')).max(128, t('validation.Size')),
    }),
  ),
)
const { defineField, handleSubmit, errors, isSubmitting, setFieldError, resetForm } = useForm({
  validationSchema: schema,
  initialValues: { currentPassword: '', newPassword: '' },
})
const [currentPassword, currentAttrs] = defineField('currentPassword')
const [newPassword, newAttrs] = defineField('newPassword')
const formError = ref<string | null>(null)

function showFieldError(field: string, message: string) {
  if (field === 'currentPassword' || field === 'newPassword') setFieldError(field, message)
  else formError.value = message
}

const submit = handleSubmit(async (values) => {
  formError.value = null
  try {
    // The old sessions are revoked: the response carries this device's new tokens.
    session.applyAuth(await change.mutateAsync(values))
  } catch (e) {
    if (isApiError(e) && e.code === 'INVALID_CREDENTIALS')
      setFieldError('currentPassword', t('settings.password.wrongCurrent'))
    else if (!applyFieldErrors(e, showFieldError)) formError.value = errorMessage(e)
    return
  }
  resetForm()
  toast.success(t('settings.password.changed'))
})
</script>

<template>
  <SettingsSection :title="t('settings.password.title')">
    <form class="grid gap-4" novalidate @submit.prevent="submit">
      <p v-if="formError" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
        {{ formError }}
      </p>
      <TextField
        v-model="currentPassword"
        v-bind="currentAttrs"
        type="password"
        :label="t('settings.password.current')"
        :error="errors.currentPassword"
        autocomplete="current-password"
      />
      <TextField
        v-model="newPassword"
        v-bind="newAttrs"
        type="password"
        :label="t('settings.password.new')"
        :error="errors.newPassword"
        :hint="t('auth.passwordHint')"
        autocomplete="new-password"
      />
      <div>
        <Button type="submit" :disabled="isSubmitting">{{ t('settings.password.submit') }}</Button>
      </div>
    </form>
  </SettingsSection>
</template>
