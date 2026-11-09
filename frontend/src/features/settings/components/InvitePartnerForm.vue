<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { z } from 'zod'
import { isApiError } from '@/shared/api/problem'
import { useCreateInvitation } from '@/shared/api/queries/account'
import { useResendVerification } from '@/shared/api/queries/me'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import TextField from '@/shared/ui/TextField.vue'

const props = defineProps<{ emailVerified: boolean }>()

const { t } = useI18n()
const invite = useCreateInvitation()
const resendMutation = useResendVerification()

const schema = computed(() =>
  toTypedSchema(
    z.object({
      email: z.string().trim().min(1, t('validation.NotBlank')).email(t('validation.Email')),
    }),
  ),
)
const { defineField, handleSubmit, errors, isSubmitting, setFieldError, resetForm } = useForm({
  validationSchema: schema,
  initialValues: { email: '' },
})
const [email, emailAttrs] = defineField('email')
const formError = ref<string | null>(null)
// The server can still say "not verified" when the page's copy of the flag was stale.
const serverUnverified = ref(false)
const blocked = computed(() => !props.emailVerified || serverUnverified.value)

function showFieldError(field: string, message: string) {
  if (field === 'email') setFieldError(field, message)
  else formError.value = message
}

const submit = handleSubmit(async (values) => {
  formError.value = null
  try {
    await invite.mutateAsync(values.email)
  } catch (e) {
    if (isApiError(e) && e.code === 'EMAIL_NOT_VERIFIED') serverUnverified.value = true
    else if (!applyFieldErrors(e, showFieldError)) formError.value = errorMessage(e)
    return
  }
  toast.success(t('settings.couple.invited', { email: values.email }))
  resetForm()
})

async function resend() {
  try {
    await resendMutation.mutateAsync()
    toast.success(t('settings.profile.resent'))
  } catch (e) {
    toast.error(errorMessage(e))
  }
}
</script>

<template>
  <form class="grid gap-4" novalidate @submit.prevent="submit">
    <p v-if="formError" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ formError }}
    </p>
    <p v-if="blocked" role="alert" class="rounded-md bg-muted p-3 text-sm text-foreground">
      {{ t('settings.couple.needsVerification') }}
    </p>
    <TextField
      v-model="email"
      v-bind="emailAttrs"
      type="email"
      :label="t('settings.couple.inviteEmail')"
      :error="errors.email"
      :disabled="blocked"
      autocomplete="off"
    />
    <div class="flex flex-wrap gap-2">
      <Button type="submit" :disabled="blocked || isSubmitting">
        {{ t('settings.couple.invite') }}
      </Button>
      <Button
        v-if="blocked"
        type="button"
        variant="outline"
        :disabled="resendMutation.isPending.value"
        @click="resend"
      >
        {{ t('settings.profile.resend') }}
      </Button>
    </div>
  </form>
</template>
