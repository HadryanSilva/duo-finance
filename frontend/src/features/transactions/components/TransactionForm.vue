<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { z } from 'zod'
import { useBankAccounts } from '@/shared/api/queries/bank-accounts'
import { useCategories } from '@/shared/api/queries/categories'
import {
  useCreateTransaction,
  useDeleteTransaction,
  useUpdateTransaction,
} from '@/shared/api/queries/transactions'
import { isApiError } from '@/shared/api/problem'
import type { TransactionKind, TransactionRequest, TransactionResponse } from '@/shared/api/types'
import { todayIso } from '@/shared/lib/dates'
import { referenceOptions } from '@/shared/lib/reference-options'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import ConfirmDialog from '@/shared/ui/ConfirmDialog.vue'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import KindSwitch from '@/shared/ui/KindSwitch.vue'
import MoneyInput from '@/shared/ui/MoneyInput.vue'
import SelectField from '@/shared/ui/SelectField.vue'
import TextField from '@/shared/ui/TextField.vue'

const props = defineProps<{
  /** Transaction being edited; absent when creating. */
  transaction?: TransactionResponse
}>()
const emit = defineEmits<{ done: [] }>()

const { t } = useI18n()
const create = useCreateTransaction()
const update = useUpdateTransaction()
const remove = useDeleteTransaction()

const KINDS = ['EXPENSE', 'INCOME', 'TRANSFER'] as const satisfies readonly TransactionKind[]
const ISO_DATE = /^\d{4}-\d{2}-\d{2}$/

const schema = computed(() =>
  toTypedSchema(
    z
      .object({
        kind: z.enum(KINDS),
        amountCents: z
          .number()
          .int()
          .nullable()
          .refine((v): v is number => v !== null && v > 0, {
            message: t('transactions.form.amountPositive'),
          }),
        date: z.string().regex(ISO_DATE, t('validation.NotNull')),
        description: z.string().max(140, t('validation.Size')),
        categoryId: z.string(),
        bankAccountId: z.string(),
        destinationBankAccountId: z.string(),
      })
      .superRefine((v, ctx) => {
        const required = (path: string) =>
          ctx.addIssue({ code: 'custom', path: [path], message: t('validation.NotNull') })
        if (v.kind !== 'TRANSFER') {
          if (!v.categoryId) required('categoryId')
          return
        }
        if (!v.bankAccountId) required('bankAccountId')
        if (!v.destinationBankAccountId) required('destinationBankAccountId')
        else if (v.destinationBankAccountId === v.bankAccountId) {
          ctx.addIssue({
            code: 'custom',
            path: ['destinationBankAccountId'],
            message: t('transactions.form.sameAccounts'),
          })
        }
      }),
  ),
)

const tx = props.transaction
const { defineField, handleSubmit, errors, isSubmitting, setFieldError, setFieldValue } = useForm({
  validationSchema: schema,
  initialValues: {
    kind: (tx?.kind ?? 'EXPENSE') as (typeof KINDS)[number],
    amountCents: (tx?.amountCents ?? null) as number | null,
    date: tx?.date ?? todayIso(),
    description: tx?.description ?? '',
    categoryId: tx?.category?.id ?? '',
    bankAccountId: tx?.bankAccount?.id ?? '',
    destinationBankAccountId: tx?.destinationBankAccount?.id ?? '',
  },
})
const [kind] = defineField('kind')
const [amount] = defineField('amountCents')
const [date, dateAttrs] = defineField('date')
const [description, descriptionAttrs] = defineField('description')
const [categoryId, categoryAttrs] = defineField('categoryId')
const [bankAccountId, bankAccountAttrs] = defineField('bankAccountId')
const [destinationId, destinationAttrs] = defineField('destinationBankAccountId')
const formError = ref<string | null>(null)

const isTransfer = computed(() => kind.value === 'TRANSFER')

// A category belongs to one kind and only a transfer has a destination.
watch(kind, (next) => {
  setFieldValue('categoryId', '')
  if (next !== 'TRANSFER') setFieldValue('destinationBankAccountId', '')
})

const categoryKind = computed(() => (kind.value === 'TRANSFER' ? undefined : kind.value))
const categoriesQuery = useCategories(categoryKind)
const bankAccountsQuery = useBankAccounts()

const archivedLabel = (name: string) => t('transactions.archivedOption', { name })

/** Active options only; when editing, the current reference stays listed even if archived. */
const categoryOptions = computed(() =>
  referenceOptions(
    categoriesQuery.data.value,
    kind.value === tx?.kind ? [tx?.category] : [],
    archivedLabel,
  ),
)
const bankAccountOptions = computed(() =>
  referenceOptions(
    bankAccountsQuery.data.value,
    [tx?.bankAccount, tx?.destinationBankAccount],
    archivedLabel,
  ),
)

const FIELDS = [
  'kind',
  'amountCents',
  'date',
  'description',
  'categoryId',
  'bankAccountId',
  'destinationBankAccountId',
] as const
type Field = (typeof FIELDS)[number]
function showFieldError(field: string, message: string) {
  const known = FIELDS.find((f) => f === field)
  if (known) setFieldError(known, message)
  else formError.value = message
}

/** Business errors that belong to one field. */
const CODE_FIELD: Partial<Record<string, Field>> = {
  RECURRING_DATE_TAKEN: 'date',
  CATEGORY_ARCHIVED: 'categoryId',
  CATEGORY_KIND_MISMATCH: 'categoryId',
  BANK_ACCOUNT_ARCHIVED: 'bankAccountId',
  TRANSFER_INVALID: 'destinationBankAccountId',
}

const submit = handleSubmit(async (values) => {
  formError.value = null
  const text = values.description.trim()
  const transfer = values.kind === 'TRANSFER'
  const body: TransactionRequest = {
    kind: values.kind,
    amountCents: values.amountCents,
    date: values.date,
    ...(text ? { description: text } : {}),
    ...(!transfer && values.categoryId ? { categoryId: values.categoryId } : {}),
    ...(values.bankAccountId ? { bankAccountId: values.bankAccountId } : {}),
    ...(transfer ? { destinationBankAccountId: values.destinationBankAccountId } : {}),
  }
  try {
    if (tx) await update.mutateAsync({ id: tx.id, body })
    else await create.mutateAsync(body)
  } catch (e) {
    if (applyFieldErrors(e, showFieldError)) return
    const field = isApiError(e) ? CODE_FIELD[e.code] : undefined
    if (field) setFieldError(field, errorMessage(e))
    else formError.value = errorMessage(e)
    return
  }
  emit('done')
})

const confirmDelete = ref(false)
async function onDelete() {
  if (!tx) return
  formError.value = null
  try {
    await remove.mutateAsync(tx.id)
  } catch (e) {
    formError.value = errorMessage(e)
    return
  }
  emit('done')
}
</script>

<template>
  <form class="grid gap-4" novalidate @submit.prevent="submit">
    <p v-if="formError" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ formError }}
    </p>

    <KindSwitch v-model="kind" :legend="t('transactions.form.kind')" />

    <MoneyInput
      v-model="amount"
      :label="t('transactions.form.amount')"
      :error="errors.amountCents"
    />
    <TextField
      v-model="date"
      v-bind="dateAttrs"
      type="date"
      :label="t('transactions.form.date')"
      :error="errors.date"
    />
    <TextField
      v-model="description"
      v-bind="descriptionAttrs"
      :label="t('transactions.form.description')"
      :error="errors.description"
      maxlength="140"
      autocomplete="off"
    />

    <template v-if="!isTransfer">
      <SelectField
        v-model="categoryId"
        v-bind="categoryAttrs"
        :label="t('transactions.form.category')"
        :options="categoryOptions"
        :placeholder="t('transactions.form.choose')"
        :error="errors.categoryId"
      />
      <SelectField
        v-model="bankAccountId"
        v-bind="bankAccountAttrs"
        :label="t('transactions.form.bankAccount')"
        :options="bankAccountOptions"
        :empty-label="t('transactions.form.noBankAccount')"
        :error="errors.bankAccountId"
      />
    </template>

    <template v-else>
      <SelectField
        v-model="bankAccountId"
        v-bind="bankAccountAttrs"
        :label="t('transactions.form.origin')"
        :options="bankAccountOptions"
        :placeholder="t('transactions.form.choose')"
        :error="errors.bankAccountId"
      />
      <SelectField
        v-model="destinationId"
        v-bind="destinationAttrs"
        :label="t('transactions.form.destination')"
        :options="bankAccountOptions"
        :placeholder="t('transactions.form.choose')"
        :error="errors.destinationBankAccountId"
      />
    </template>

    <div class="flex flex-wrap items-center justify-between gap-2 pt-2">
      <Button
        v-if="transaction"
        type="button"
        variant="destructive"
        :disabled="remove.isPending.value"
        @click="confirmDelete = true"
      >
        {{ t('common.delete') }}
      </Button>
      <Button type="submit" class="ml-auto" :disabled="isSubmitting">
        {{ t('common.save') }}
      </Button>
    </div>

    <ConfirmDialog
      v-model:open="confirmDelete"
      :title="t('transactions.deleteTitle')"
      :message="t('transactions.deleteMessage')"
      :confirm-label="t('common.delete')"
      destructive
      @confirm="onDelete"
    />
  </form>
</template>
