<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { z } from 'zod'
import TextField from '@/shared/ui/TextField.vue'
import { isApiError } from '@/shared/api/problem'
import { useCreateInvitation } from '@/shared/api/queries/account'
import { useMe, useResendVerification } from '@/shared/api/queries/me'
import { errorMessage } from '@/shared/i18n/error-codes'
import { useSession } from '@/shared/session/session-store'
import { Button } from '@/shared/ui/button'
import { applyFieldErrors } from '@/shared/ui/FormErrors'

const emit = defineEmits<{ done: [] }>()

const { t } = useI18n()
const session = useSession()
const me = useMe()
const invite = useCreateInvitation()
const resendMutation = useResendVerification()

const schema = computed(() =>
  toTypedSchema(
    z.object({
      email: z.string().trim().min(1, t('validation.NotBlank')).email(t('validation.Email')),
    }),
  ),
)
const { defineField, handleSubmit, errors, isSubmitting, setFieldError } = useForm({
  validationSchema: schema,
  initialValues: { email: '' },
})
const [email, emailAttrs] = defineField('email')

const sentTo = ref<string | null>(null)
const formError = ref<string | null>(null)
const serverUnverified = ref(false)
const resent = ref(false)
const resendError = ref<string | null>(null)

// /me is fresher than the session, which was restored before any verification in another tab.
const emailVerified = computed(
  () => me.data.value?.emailVerified ?? session.user?.emailVerified ?? false,
)
const unverified = computed(() => serverUnverified.value || !emailVerified.value)

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
  sentTo.value = values.email
})

async function resend() {
  resent.value = false
  resendError.value = null
  try {
    await resendMutation.mutateAsync()
    resent.value = true
  } catch (e) {
    resendError.value = errorMessage(e)
  }
}
</script>

<template>
  <div v-if="sentTo" class="grid gap-4">
    <p role="status" class="rounded-md bg-muted p-3 text-sm text-foreground">
      {{ t('onboarding.invite.sent', { email: sentTo }) }}
    </p>
    <Button @click="emit('done')">{{ t('common.continue') }}</Button>
  </div>
  <div v-else-if="unverified" class="grid gap-4">
    <p role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ t('errors.EMAIL_NOT_VERIFIED') }}
    </p>
    <p v-if="resent" role="status" class="text-sm text-muted-foreground">
      {{ t('onboarding.invite.resent') }}
    </p>
    <p v-if="resendError" role="alert" class="text-sm text-destructive">{{ resendError }}</p>
    <Button variant="outline" :disabled="resendMutation.isPending.value" @click="resend">
      {{ t('onboarding.invite.resend') }}
    </Button>
    <Button variant="ghost" @click="emit('done')">{{ t('common.skip') }}</Button>
  </div>
  <form v-else class="grid gap-4" novalidate @submit.prevent="submit">
    <p v-if="formError" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ formError }}
    </p>
    <TextField
      v-model="email"
      v-bind="emailAttrs"
      :label="t('onboarding.invite.email')"
      :error="errors.email"
      type="email"
      autocomplete="off"
    />
    <Button type="submit" :disabled="isSubmitting">{{ t('onboarding.invite.send') }}</Button>
    <Button type="button" variant="ghost" @click="emit('done')">{{ t('common.skip') }}</Button>
  </form>
</template>
