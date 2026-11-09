<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { z } from 'zod'
import { useResendVerification, useUpdateMe } from '@/shared/api/queries/me'
import { errorMessage } from '@/shared/i18n/error-codes'
import { useSession } from '@/shared/session/session-store'
import { Badge } from '@/shared/ui/badge'
import { Button } from '@/shared/ui/button'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import TextField from '@/shared/ui/TextField.vue'
import SettingsSection from './SettingsSection.vue'

defineProps<{ emailVerified: boolean }>()

const { t } = useI18n()
const session = useSession()
const update = useUpdateMe()
const resendMutation = useResendVerification()

const schema = computed(() =>
  toTypedSchema(
    z.object({
      displayName: z.string().trim().min(1, t('validation.NotBlank')).max(60, t('validation.Size')),
    }),
  ),
)
const { defineField, handleSubmit, errors, isSubmitting, setFieldError } = useForm({
  validationSchema: schema,
  initialValues: { displayName: session.user?.displayName ?? '' },
})
const [displayName, displayNameAttrs] = defineField('displayName')
const formError = ref<string | null>(null)

function showFieldError(field: string, message: string) {
  if (field === 'displayName') setFieldError(field, message)
  else formError.value = message
}

const submit = handleSubmit(async (values) => {
  formError.value = null
  try {
    session.updateUser(await update.mutateAsync({ displayName: values.displayName }))
  } catch (e) {
    if (!applyFieldErrors(e, showFieldError)) formError.value = errorMessage(e)
    return
  }
  toast.success(t('settings.profile.saved'))
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
  <SettingsSection :title="t('settings.profile.title')">
    <form class="grid gap-4" novalidate @submit.prevent="submit">
      <p v-if="formError" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
        {{ formError }}
      </p>
      <TextField
        v-model="displayName"
        v-bind="displayNameAttrs"
        :label="t('settings.profile.name')"
        :error="errors.displayName"
        autocomplete="name"
      />
      <div class="grid gap-2">
        <span class="text-sm font-medium text-foreground">{{ t('settings.profile.email') }}</span>
        <div class="flex flex-wrap items-center gap-2">
          <span class="text-sm text-foreground">{{ session.user?.email }}</span>
          <Badge v-if="!emailVerified" variant="secondary">
            {{ t('settings.profile.unverified') }}
          </Badge>
          <Button
            v-if="!emailVerified"
            type="button"
            size="sm"
            variant="outline"
            :disabled="resendMutation.isPending.value"
            @click="resend"
          >
            {{ t('settings.profile.resend') }}
          </Button>
        </div>
      </div>
      <div>
        <Button type="submit" :disabled="isSubmitting">{{ t('common.save') }}</Button>
      </div>
    </form>
  </SettingsSection>
</template>
