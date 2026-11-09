<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { z } from 'zod'
import type { CreatedAccessCodeResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import TextField from '@/shared/ui/TextField.vue'
import { useCreateAccessCode, type CreateAccessCodeBody } from '../api/admin-api'

const MAX_USES = 1000
const NOTE_MAX = 255 // CreateAccessCodeRequest.note @Size(max = 255)

const emit = defineEmits<{ created: [result: CreatedAccessCodeResponse] }>()

const { t } = useI18n()
const create = useCreateAccessCode()

const schema = computed(() =>
  toTypedSchema(
    z.object({
      email: z
        .string()
        .trim()
        .refine((v) => v === '' || z.string().email().safeParse(v).success, t('validation.Email')),
      maxUses: z
        .string()
        .trim()
        .refine(
          (v) => /^\d+$/.test(v) && Number(v) >= 1 && Number(v) <= MAX_USES,
          t('admin.form.maxUsesRange'),
        ),
      expiresAt: z.string(),
      note: z.string().trim().max(NOTE_MAX, t('validation.Size')),
    }),
  ),
)
const { defineField, handleSubmit, errors, isSubmitting, setFieldError } = useForm({
  validationSchema: schema,
  initialValues: { email: '', maxUses: '1', expiresAt: '', note: '' },
})
const [email, emailAttrs] = defineField('email')
const [maxUses, maxUsesAttrs] = defineField('maxUses')
const [expiresAt, expiresAtAttrs] = defineField('expiresAt')
const [note, noteAttrs] = defineField('note')
const formError = ref<string | null>(null)

function showFieldError(field: string, message: string) {
  if (field === 'email' || field === 'maxUses' || field === 'expiresAt' || field === 'note') {
    setFieldError(field, message)
  } else {
    formError.value = message
  }
}

const submit = handleSubmit(async (values) => {
  formError.value = null
  const body: CreateAccessCodeBody = { maxUses: Number(values.maxUses) }
  if (values.email) body.email = values.email
  // datetime-local is a wall-clock value in the admin's zone; the API wants an Instant.
  if (values.expiresAt) body.expiresAt = new Date(values.expiresAt).toISOString()
  if (values.note) body.note = values.note
  try {
    emit('created', await create.mutateAsync(body))
  } catch (e) {
    if (!applyFieldErrors(e, showFieldError)) formError.value = errorMessage(e)
  }
})
</script>

<template>
  <form class="grid gap-4" novalidate @submit.prevent="submit">
    <p v-if="formError" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ formError }}
    </p>
    <TextField
      v-model="email"
      v-bind="emailAttrs"
      type="email"
      :label="t('admin.form.email')"
      :hint="t('admin.form.emailHint')"
      :error="errors.email"
      autocomplete="off"
    />
    <TextField
      v-model="maxUses"
      v-bind="maxUsesAttrs"
      inputmode="numeric"
      :label="t('admin.form.maxUses')"
      :error="errors.maxUses"
    />
    <TextField
      v-model="expiresAt"
      v-bind="expiresAtAttrs"
      type="datetime-local"
      :label="t('admin.form.expiresAt')"
      :error="errors.expiresAt"
    />
    <TextField
      v-model="note"
      v-bind="noteAttrs"
      :maxlength="NOTE_MAX"
      :label="t('admin.form.note')"
      :error="errors.note"
    />
    <div>
      <Button type="submit" :disabled="isSubmitting">{{ t('admin.form.submit') }}</Button>
    </div>
  </form>
</template>
