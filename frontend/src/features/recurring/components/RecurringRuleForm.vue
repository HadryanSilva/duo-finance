<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { z } from 'zod'
import { useBankAccounts } from '@/shared/api/queries/bank-accounts'
import { useCategories } from '@/shared/api/queries/categories'
import { isApiError } from '@/shared/api/problem'
import type {
  CreateRecurringRuleRequest,
  RecurringFrequency,
  RecurringRuleResponse,
  TransactionKind,
  UpdateRecurringRuleRequest,
} from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { formatBusinessDate, todayIso } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { referenceOptions } from '@/shared/lib/reference-options'
import { Button } from '@/shared/ui/button'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import KindSwitch from '@/shared/ui/KindSwitch.vue'
import MoneyInput from '@/shared/ui/MoneyInput.vue'
import SelectField from '@/shared/ui/SelectField.vue'
import TextField from '@/shared/ui/TextField.vue'
import { useCreateRecurringRule, useUpdateRecurringRule } from '../api/recurring-api'

const props = defineProps<{
  /** Rule being edited; absent when creating. */
  rule?: RecurringRuleResponse
}>()
const emit = defineEmits<{ done: [] }>()

const { t, locale } = useI18n()
const create = useCreateRecurringRule()
const update = useUpdateRecurringRule()

const KINDS = ['EXPENSE', 'INCOME', 'TRANSFER'] as const satisfies readonly TransactionKind[]
const FREQUENCIES = ['WEEKLY', 'MONTHLY', 'YEARLY'] as const satisfies readonly RecurringFrequency[]
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
        description: z.string().max(140, t('validation.Size')),
        categoryId: z.string(),
        bankAccountId: z.string(),
        destinationBankAccountId: z.string(),
        frequency: z.enum(FREQUENCIES),
        startDate: z.string().regex(ISO_DATE, t('validation.NotNull')),
        endDate: z.string(),
      })
      .superRefine((v, ctx) => {
        const issue = (path: string, message: string) =>
          ctx.addIssue({ code: 'custom', path: [path], message })
        if (v.endDate && (!ISO_DATE.test(v.endDate) || v.endDate < v.startDate)) {
          issue('endDate', t('recurring.form.endBeforeStart'))
        }
        if (v.kind !== 'TRANSFER') {
          if (!v.categoryId) issue('categoryId', t('validation.NotNull'))
          return
        }
        if (!v.bankAccountId) issue('bankAccountId', t('validation.NotNull'))
        if (!v.destinationBankAccountId) issue('destinationBankAccountId', t('validation.NotNull'))
        else if (v.destinationBankAccountId === v.bankAccountId) {
          issue('destinationBankAccountId', t('transactions.form.sameAccounts'))
        }
      }),
  ),
)

const rule = props.rule
const { defineField, handleSubmit, errors, isSubmitting, setFieldError, setFieldValue } = useForm({
  validationSchema: schema,
  initialValues: {
    kind: (rule?.kind ?? 'EXPENSE') as (typeof KINDS)[number],
    amountCents: (rule?.amountCents ?? null) as number | null,
    description: rule?.description ?? '',
    categoryId: rule?.category?.id ?? '',
    bankAccountId: rule?.bankAccount?.id ?? '',
    destinationBankAccountId: rule?.destinationBankAccount?.id ?? '',
    frequency: (rule?.frequency ?? 'MONTHLY') as (typeof FREQUENCIES)[number],
    startDate: rule?.startDate ?? todayIso(),
    endDate: rule?.endDate ?? '',
  },
})
const [kind] = defineField('kind')
const [amount] = defineField('amountCents')
const [description, descriptionAttrs] = defineField('description')
const [categoryId] = defineField('categoryId')
const [bankAccountId] = defineField('bankAccountId')
const [destinationId] = defineField('destinationBankAccountId')
const [frequency] = defineField('frequency')
const [startDate, startDateAttrs] = defineField('startDate')
const [endDate, endDateAttrs] = defineField('endDate')
const formError = ref<string | null>(null)

const isTransfer = computed(() => kind.value === 'TRANSFER')
/** Creating from today or earlier generates the due occurrences immediately. */
const generatesNow = computed(
  () => !rule && ISO_DATE.test(startDate.value ?? '') && (startDate.value ?? '') <= todayIso(),
)

// A category belongs to one kind and only a transfer has a destination.
watch(kind, (next) => {
  setFieldValue('categoryId', '')
  if (next !== 'TRANSFER') setFieldValue('destinationBankAccountId', '')
})

const categoryKind = computed(() => (kind.value === 'TRANSFER' ? undefined : kind.value))
const categoriesQuery = useCategories(categoryKind)
const bankAccountsQuery = useBankAccounts()
const archivedLabel = (name: string) => t('transactions.archivedOption', { name })

const categoryOptions = computed(() =>
  referenceOptions(categoriesQuery.data.value, [rule?.category], archivedLabel),
)
const bankAccountOptions = computed(() =>
  referenceOptions(
    bankAccountsQuery.data.value,
    [rule?.bankAccount, rule?.destinationBankAccount],
    archivedLabel,
  ),
)
const frequencyLabeled = computed(() =>
  FREQUENCIES.map((f) => ({ id: f, label: t(`recurring.frequency.${f}`) })),
)

const FIELDS = [
  'kind',
  'amountCents',
  'description',
  'categoryId',
  'bankAccountId',
  'destinationBankAccountId',
  'frequency',
  'startDate',
  'endDate',
] as const
type Field = (typeof FIELDS)[number]
function showFieldError(field: string, message: string) {
  const known = FIELDS.find((f) => f === field)
  if (known) setFieldError(known, message)
  else formError.value = message
}

/** Business errors that belong to one field. */
const CODE_FIELD: Partial<Record<string, Field>> = {
  CATEGORY_ARCHIVED: 'categoryId',
  CATEGORY_KIND_MISMATCH: 'categoryId',
  BANK_ACCOUNT_ARCHIVED: 'bankAccountId',
  TRANSFER_INVALID: 'destinationBankAccountId',
}

const submit = handleSubmit(async (values) => {
  formError.value = null
  const text = values.description.trim()
  const transfer = values.kind === 'TRANSFER'
  const shared = {
    amountCents: values.amountCents,
    ...(text ? { description: text } : {}),
    ...(!transfer && values.categoryId ? { categoryId: values.categoryId } : {}),
    ...(values.bankAccountId ? { bankAccountId: values.bankAccountId } : {}),
    ...(transfer ? { destinationBankAccountId: values.destinationBankAccountId } : {}),
    ...(values.endDate ? { endDate: values.endDate } : {}),
  }
  try {
    if (rule) {
      const body: UpdateRecurringRuleRequest = shared
      await update.mutateAsync({ id: rule.id, body })
    } else {
      const body: CreateRecurringRuleRequest = {
        ...shared,
        kind: values.kind,
        frequency: values.frequency,
        startDate: values.startDate,
      }
      await create.mutateAsync(body)
    }
  } catch (e) {
    if (applyFieldErrors(e, showFieldError)) return
    const field = isApiError(e) ? CODE_FIELD[e.code] : undefined
    if (field) setFieldError(field, errorMessage(e))
    else formError.value = errorMessage(e)
    return
  }
  emit('done')
})

const lockedStart = computed(() =>
  rule ? formatBusinessDate(rule.startDate, locale.value as Locale) : '',
)
</script>

<template>
  <form class="grid gap-4" novalidate @submit.prevent="submit">
    <p v-if="formError" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ formError }}
    </p>

    <template v-if="rule">
      <dl class="grid gap-3 rounded-md bg-muted p-3 text-sm">
        <div class="flex justify-between gap-4">
          <dt class="text-muted-foreground">{{ t('transactions.form.kind') }}</dt>
          <dd class="font-medium text-foreground">{{ t(`transactions.kind.${rule.kind}`) }}</dd>
        </div>
        <div class="flex justify-between gap-4">
          <dt class="text-muted-foreground">{{ t('recurring.form.frequency') }}</dt>
          <dd class="font-medium text-foreground">
            {{ t(`recurring.frequency.${rule.frequency}`) }}
          </dd>
        </div>
        <div class="flex justify-between gap-4">
          <dt class="text-muted-foreground">{{ t('recurring.form.startDate') }}</dt>
          <dd class="font-medium text-foreground">{{ lockedStart }}</dd>
        </div>
      </dl>
      <p class="text-sm text-muted-foreground">{{ t('recurring.form.lockedNote') }}</p>
    </template>
    <KindSwitch v-else v-model="kind" :legend="t('transactions.form.kind')" />

    <MoneyInput
      v-model="amount"
      :label="t('transactions.form.amount')"
      :error="errors.amountCents"
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
        :label="t('transactions.form.category')"
        :options="categoryOptions"
        :placeholder="t('transactions.form.choose')"
        :error="errors.categoryId"
      />
      <SelectField
        v-model="bankAccountId"
        :label="t('transactions.form.bankAccount')"
        :options="bankAccountOptions"
        :empty-label="t('transactions.form.noBankAccount')"
        :error="errors.bankAccountId"
      />
    </template>
    <template v-else>
      <SelectField
        v-model="bankAccountId"
        :label="t('transactions.form.origin')"
        :options="bankAccountOptions"
        :placeholder="t('transactions.form.choose')"
        :error="errors.bankAccountId"
      />
      <SelectField
        v-model="destinationId"
        :label="t('transactions.form.destination')"
        :options="bankAccountOptions"
        :placeholder="t('transactions.form.choose')"
        :error="errors.destinationBankAccountId"
      />
    </template>

    <template v-if="!rule">
      <SelectField
        v-model="frequency"
        :label="t('recurring.form.frequency')"
        :options="frequencyLabeled"
        :error="errors.frequency"
      />
      <TextField
        v-model="startDate"
        v-bind="startDateAttrs"
        type="date"
        :label="t('recurring.form.startDate')"
        :error="errors.startDate"
      />
    </template>
    <TextField
      v-model="endDate"
      v-bind="endDateAttrs"
      type="date"
      :min="startDate"
      :label="t('recurring.form.endDate')"
      :error="errors.endDate"
    />

    <p
      v-if="generatesNow"
      role="status"
      class="rounded-md border border-border bg-muted p-3 text-sm text-foreground"
    >
      {{ t('recurring.form.immediateNotice') }}
    </p>

    <div class="flex justify-end pt-2">
      <Button type="submit" :disabled="isSubmitting">{{ t('common.save') }}</Button>
    </div>
  </form>
</template>
