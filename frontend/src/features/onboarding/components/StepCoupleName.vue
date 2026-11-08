<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { z } from 'zod'
import TextField from '@/features/auth/components/TextField.vue'
import { useRenameAccount } from '@/shared/api/queries/account'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import { applyFieldErrors } from '@/shared/ui/FormErrors'

const props = defineProps<{ initialName: string }>()
const emit = defineEmits<{ done: [] }>()

const { t } = useI18n()
const rename = useRenameAccount()

// 1-60 characters after trimming.
const schema = computed(() =>
  toTypedSchema(
    z.object({
      name: z.string().trim().min(1, t('validation.NotBlank')).max(60, t('validation.Size')),
    }),
  ),
)
const { defineField, handleSubmit, errors, isSubmitting, setFieldError } = useForm({
  validationSchema: schema,
  initialValues: { name: props.initialName },
})
const [name, nameAttrs] = defineField('name')
const formError = ref<string | null>(null)

function showFieldError(field: string, message: string) {
  if (field === 'name') setFieldError(field, message)
  else formError.value = message
}

const submit = handleSubmit(async (values) => {
  formError.value = null
  try {
    await rename.mutateAsync(values.name)
  } catch (e) {
    if (!applyFieldErrors(e, showFieldError)) formError.value = errorMessage(e)
    return
  }
  emit('done')
})
</script>

<template>
  <form class="grid gap-4" novalidate @submit.prevent="submit">
    <p v-if="formError" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ formError }}
    </p>
    <TextField
      v-model="name"
      v-bind="nameAttrs"
      :label="t('onboarding.couple.name')"
      :error="errors.name"
      autocomplete="off"
    />
    <Button type="submit" :disabled="isSubmitting">{{ t('common.continue') }}</Button>
  </form>
</template>
