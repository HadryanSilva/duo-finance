<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { z } from 'zod'
import { useAccount } from '@/shared/api/queries/account'
import { useCreateBankAccount, useUpdateBankAccount } from '@/shared/api/queries/bank-accounts'
import type { BankAccountResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import { Label } from '@/shared/ui/label'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import MoneyInput from '@/shared/ui/MoneyInput.vue'
import TextField from '@/shared/ui/TextField.vue'

const props = defineProps<{ account?: BankAccountResponse }>()
const emit = defineEmits<{ done: [] }>()

const { t } = useI18n()
const accountQuery = useAccount()
const create = useCreateBankAccount()
const update = useUpdateBankAccount()

const TYPES = ['CHECKING', 'SAVINGS', 'CASH', 'OTHER'] as const

const members = computed(() => accountQuery.data.value?.members ?? [])
// A holder who left the couple stays selectable so saving does not clear it by accident.
const formerHolderId = computed(() => {
  const id = props.account?.holderUserId
  return id && !members.value.some((m) => m.userId === id) ? id : null
})

const schema = computed(() =>
  toTypedSchema(
    z.object({
      name: z.string().trim().min(1, t('validation.NotBlank')).max(60, t('validation.Size')),
      type: z.enum(TYPES, { message: t('validation.Invalid') }),
      initialBalanceCents: z
        .number()
        .int()
        .nullable()
        .refine((v): v is number => v !== null, { message: t('validation.Invalid') }),
      holderUserId: z.string(),
    }),
  ),
)
const { defineField, handleSubmit, errors, isSubmitting, setFieldError } = useForm({
  validationSchema: schema,
  initialValues: {
    name: props.account?.name ?? '',
    type: props.account?.type ?? ('CHECKING' as (typeof TYPES)[number]),
    initialBalanceCents: (props.account?.initialBalanceCents ?? 0) as number | null,
    holderUserId: props.account?.holderUserId ?? '',
  },
})
const [name, nameAttrs] = defineField('name')
const [type, typeAttrs] = defineField('type')
const [balance] = defineField('initialBalanceCents')
const [holder, holderAttrs] = defineField('holderUserId')
const formError = ref<string | null>(null)

const FIELDS = ['name', 'type', 'initialBalanceCents', 'holderUserId'] as const
function showFieldError(field: string, message: string) {
  const known = FIELDS.find((f) => f === field)
  if (known) setFieldError(known, message)
  else formError.value = message
}

const submit = handleSubmit(async (values) => {
  formError.value = null
  // Full PUT: leaving the holder out clears it.
  const body = {
    name: values.name,
    type: values.type,
    initialBalanceCents: values.initialBalanceCents,
    ...(values.holderUserId ? { holderUserId: values.holderUserId } : {}),
  }
  try {
    if (props.account) await update.mutateAsync({ id: props.account.id, body })
    else await create.mutateAsync(body)
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
      :label="t('bankAccounts.form.name')"
      :error="errors.name"
      autocomplete="off"
    />
    <div class="grid gap-2">
      <Label for="bank-account-type">{{ t('bankAccounts.form.type') }}</Label>
      <select
        id="bank-account-type"
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
      :label="t('bankAccounts.form.balance')"
      :error="errors.initialBalanceCents"
      allow-negative
    />
    <div class="grid gap-2">
      <Label for="bank-account-holder">{{ t('bankAccounts.form.holder') }}</Label>
      <select
        id="bank-account-holder"
        v-model="holder"
        v-bind="holderAttrs"
        class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        <option value="">{{ t('bankAccounts.form.noHolder') }}</option>
        <option v-for="m in members" :key="m.userId" :value="m.userId">{{ m.displayName }}</option>
        <option v-if="formerHolderId" :value="formerHolderId">
          {{ t('bankAccounts.formerMember') }}
        </option>
      </select>
      <p v-if="errors.holderUserId" class="text-sm text-destructive">{{ errors.holderUserId }}</p>
    </div>
    <Button type="submit" :disabled="isSubmitting">{{ t('common.save') }}</Button>
  </form>
</template>
