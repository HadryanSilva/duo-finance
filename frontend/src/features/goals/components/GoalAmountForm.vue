<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { z } from 'zod'
import { useDeposit, useWithdraw } from '@/shared/api/queries/goals'
import type { GoalResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import MoneyInput from '@/shared/ui/MoneyInput.vue'
import MoneyText from '@/shared/ui/MoneyText.vue'
import TextField from '@/shared/ui/TextField.vue'

const props = defineProps<{ goal: GoalResponse; mode: 'deposit' | 'withdraw' }>()
const emit = defineEmits<{ done: [] }>()

const { t } = useI18n()
const deposit = useDeposit()
const withdraw = useWithdraw()

const schema = computed(() =>
  toTypedSchema(
    z.object({
      amountCents: z
        .number()
        .int()
        .nullable()
        .refine((v): v is number => v !== null && v > 0, {
          message: t('goals.amount.amountPositive'),
        }),
      note: z.string(),
    }),
  ),
)

const { defineField, handleSubmit, errors, isSubmitting, setFieldError } = useForm({
  validationSchema: schema,
  initialValues: { amountCents: null as number | null, note: '' },
})
const [amount] = defineField('amountCents')
const [note] = defineField('note')
const formError = ref<string | null>(null)

const submit = handleSubmit(async (values) => {
  formError.value = null
  const body = {
    amountCents: values.amountCents as number,
    note: values.note.trim() || undefined,
  }
  try {
    const mutation = props.mode === 'deposit' ? deposit : withdraw
    await mutation.mutateAsync({ id: props.goal.id, body })
  } catch (e) {
    const shown = applyFieldErrors(e, (field, message) => {
      if (field === 'amountCents' || field === 'note') setFieldError(field, message)
      else formError.value = message
    })
    if (!shown) formError.value = errorMessage(e)
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
    <p class="text-sm text-muted-foreground">
      <span class="font-medium text-foreground">{{ goal.name }}</span>
      ·
      <i18n-t
        :keypath="mode === 'withdraw' ? 'goals.amount.available' : 'goals.amount.missing'"
        tag="span"
      >
        <template #amount>
          <MoneyText :cents="mode === 'withdraw' ? goal.savedCents : goal.remainingCents" />
        </template>
      </i18n-t>
    </p>
    <MoneyInput v-model="amount" :label="t('goals.amount.amount')" :error="errors.amountCents" />
    <TextField v-model="note" :label="t('goals.amount.note')" :error="errors.note" />
    <div class="flex justify-end pt-2">
      <Button type="submit" :disabled="isSubmitting">{{ t(`goals.amount.${mode}`) }}</Button>
    </div>
  </form>
</template>
