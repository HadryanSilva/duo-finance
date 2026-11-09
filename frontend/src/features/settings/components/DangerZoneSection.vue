<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import { z } from 'zod'
import { isApiError } from '@/shared/api/problem'
import { useDeleteMe } from '@/shared/api/queries/me'
import { errorMessage } from '@/shared/i18n/error-codes'
import { useSession } from '@/shared/session/session-store'
import { Button } from '@/shared/ui/button'
import ConfirmDialog from '@/shared/ui/ConfirmDialog.vue'
import TextField from '@/shared/ui/TextField.vue'
import SettingsSection from './SettingsSection.vue'

const { t } = useI18n()
const router = useRouter()
const session = useSession()
const deleteMe = useDeleteMe()

const schema = computed(() =>
  toTypedSchema(z.object({ password: z.string().min(1, t('validation.NotBlank')) })),
)
const { defineField, handleSubmit, errors, setFieldError } = useForm({
  validationSchema: schema,
  initialValues: { password: '' },
})
const [password, passwordAttrs] = defineField('password')
const formError = ref<string | null>(null)
const confirmOpen = ref(false)

// The password is required before the confirmation even opens.
const askConfirmation = handleSubmit(() => {
  formError.value = null
  confirmOpen.value = true
})

async function deleteAccount() {
  formError.value = null
  try {
    await deleteMe.mutateAsync(password.value ?? '')
  } catch (e) {
    if (isApiError(e) && e.code === 'INVALID_CREDENTIALS')
      setFieldError('password', t('settings.danger.wrongPassword'))
    else formError.value = errorMessage(e)
    return
  }
  // The account is gone, and so is the server session: no backend logout, just forget it.
  session.endSession()
  toast.success(t('settings.danger.deleted'))
  await router.push('/')
}
</script>

<template>
  <SettingsSection
    :title="t('settings.danger.title')"
    :description="t('settings.danger.description')"
  >
    <form class="grid gap-4" novalidate @submit.prevent="askConfirmation">
      <p v-if="formError" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
        {{ formError }}
      </p>
      <TextField
        v-model="password"
        v-bind="passwordAttrs"
        type="password"
        :label="t('settings.danger.password')"
        :error="errors.password"
        autocomplete="current-password"
      />
      <div>
        <Button type="submit" variant="destructive" :disabled="deleteMe.isPending.value">
          {{ t('settings.danger.delete') }}
        </Button>
      </div>
    </form>
    <ConfirmDialog
      v-model:open="confirmOpen"
      destructive
      :title="t('settings.danger.confirmTitle')"
      :message="t('settings.danger.confirmMessage')"
      :confirm-label="t('settings.danger.confirm')"
      @confirm="deleteAccount"
    />
  </SettingsSection>
</template>
