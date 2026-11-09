<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { z } from 'zod'
import TextField from '@/shared/ui/TextField.vue'
import { useCreateBankAccount } from '@/shared/api/queries/bank-accounts'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import { Label } from '@/shared/ui/label'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import MoneyInput from '@/shared/ui/MoneyInput.vue'

const emit = defineEmits<{ done: [] }>()

const { t } = useI18n()
const create = useCreateBankAccount()

const TYPES = ['CHECKING', 'SAVINGS', 'CASH', 'OTHER'] as const
type BankType = (typeof TYPES)[number]

const schema = computed(() =>
  toTypedSchema(
    z.object({
      // 1-60 characters after trimming.
      name: z.string().trim().min(1, t('validation.NotBlank')).max(60, t('validation.Size')),
      type: z.enum(TYPES, { message: t('validation.Invalid') }),
      initialBalanceCents: z
        .number()
        .int()
        .nullable()
        .refine((v): v is number => v !== null, { message: t('validation.Invalid') }),
    }),
  ),
)
const { defineField, handleSubmit, errors, isSubmitting, setFieldError } = useForm({
  validationSchema: schema,
  initialValues: {
    name: '',
    type: 'CHECKING' as BankType,
    initialBalanceCents: 0 as number | null,
  },
})
const [name, nameAttrs] = defineField('name')
const [type, typeAttrs] = defineField('type')
const [balance] = defineField('initialBalanceCents')
const formError = ref<string | null>(null)

function showFieldError(field: string, message: string) {
  if (field === 'name' || field === 'type' || field === 'initialBalanceCents') {
    setFieldError(field, message)
  } else {
    formError.value = message
  }
}

const submit = handleSubmit(async (values) => {
  formError.value = null
  try {
    await create.mutateAsync({
      name: values.name,
      type: values.type,
      initialBalanceCents: values.initialBalanceCents,
    })
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
      :label="t('onboarding.bank.name')"
      :error="errors.name"
      autocomplete="off"
    />
    <div class="grid gap-2">
      <Label for="onboarding-bank-type">{{ t('onboarding.bank.type') }}</Label>
      <select
        id="onboarding-bank-type"
        v-model="type"
        v-bind="typeAttrs"
        class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        <option v-for="o in TYPES" :key="o" :value="o">{{ t(`bankAccountType.${o}`) }}</option>
      </select>
      <p v-if="errors.type" class="text-sm text-destructive">{{ errors.type }}</p>
    </div>
    <MoneyInput
      v-model="balance"
      :label="t('onboarding.bank.balance')"
      :error="errors.initialBalanceCents"
      allow-negative
    />
    <Button type="submit" :disabled="isSubmitting">{{ t('onboarding.bank.submit') }}</Button>
    <Button type="button" variant="ghost" @click="emit('done')">{{ t('common.skip') }}</Button>
  </form>
</template>
