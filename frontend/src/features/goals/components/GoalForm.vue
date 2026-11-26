<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { z } from 'zod'
import { useCreateGoal, useUpdateGoal } from '@/shared/api/queries/goals'
import { isApiError } from '@/shared/api/problem'
import type { CreateGoalRequest, GoalResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { todayIso } from '@/shared/lib/dates'
import { Button } from '@/shared/ui/button'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import MoneyInput from '@/shared/ui/MoneyInput.vue'
import TextField from '@/shared/ui/TextField.vue'

const props = defineProps<{ goal?: GoalResponse }>()
const emit = defineEmits<{ done: [] }>()

const { t } = useI18n()
const create = useCreateGoal()
const update = useUpdateGoal()

const schema = computed(() =>
  toTypedSchema(
    z.object({
      name: z.string().trim().min(1, t('validation.NotBlank')),
      targetCents: z
        .number()
        .int()
        .nullable()
        .refine((v): v is number => v !== null && v > 0, {
          message: t('goals.form.targetPositive'),
        }),
      // A date already passed is kept when editing (the page flags it); a new one must not be past.
      targetDate: z
        .string()
        .refine((v) => v === '' || v >= todayIso() || v === props.goal?.targetDate, {
          message: t('goals.form.targetDatePast'),
        }),
      monthlyPercent: z
        .string()
        .trim()
        .regex(/^\d{1,3}$/, t('goals.form.percentRange'))
        .refine((v) => Number(v) <= 100, { message: t('goals.form.percentRange') }),
      revolutPocket: z.string().trim().max(100, t('goals.form.pocketMax')),
    }),
  ),
)

const goal = props.goal
const { defineField, handleSubmit, errors, isSubmitting, setFieldError } = useForm({
  validationSchema: schema,
  initialValues: {
    name: goal?.name ?? '',
    targetCents: (goal?.targetCents ?? null) as number | null,
    targetDate: goal?.targetDate ?? '',
    monthlyPercent: String(goal?.monthlyPercent ?? 0),
    revolutPocket: goal?.revolutPocket ?? '',
  },
})
const [name] = defineField('name')
const [target] = defineField('targetCents')
const [targetDate] = defineField('targetDate')
const [percent] = defineField('monthlyPercent')
const [pocket] = defineField('revolutPocket')
const formError = ref<string | null>(null)

const FIELD_CODES: Record<string, 'name' | 'monthlyPercent' | 'revolutPocket'> = {
  GOAL_NAME_TAKEN: 'name',
  GOAL_PERCENT_CAP: 'monthlyPercent',
  GOAL_POCKET_TAKEN: 'revolutPocket',
}

const submit = handleSubmit(async (values) => {
  formError.value = null
  // No date or pocket is sent as a missing field, which the API reads as null (also clearing it on edit).
  const body: CreateGoalRequest = {
    name: values.name.trim(),
    targetCents: values.targetCents as number,
    targetDate: values.targetDate || undefined,
    monthlyPercent: Number(values.monthlyPercent),
    revolutPocket: values.revolutPocket || undefined,
  }
  try {
    if (goal) await update.mutateAsync({ id: goal.id, body })
    else await create.mutateAsync(body)
  } catch (e) {
    const shown = applyFieldErrors(e, (field, message) => {
      if (
        field === 'name' ||
        field === 'targetCents' ||
        field === 'monthlyPercent' ||
        field === 'revolutPocket'
      ) {
        setFieldError(field, message)
      } else {
        formError.value = message
      }
    })
    if (shown) return
    const field = isApiError(e) ? FIELD_CODES[e.code] : undefined
    if (field) setFieldError(field, errorMessage(e))
    else formError.value = errorMessage(e)
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
    <TextField v-model="name" :label="t('goals.form.name')" :error="errors.name" />
    <MoneyInput v-model="target" :label="t('goals.form.target')" :error="errors.targetCents" />
    <TextField
      v-model="targetDate"
      type="date"
      :label="t('goals.form.targetDate')"
      :error="errors.targetDate"
    />
    <TextField
      v-model="percent"
      inputmode="numeric"
      :label="t('goals.form.percent')"
      :hint="t('goals.form.percentHint')"
      :error="errors.monthlyPercent"
    />
    <TextField
      v-model="pocket"
      :label="t('goals.form.pocket')"
      :hint="t('goals.form.pocketHint')"
      :error="errors.revolutPocket"
    />
    <div class="flex justify-end pt-2">
      <Button type="submit" :disabled="isSubmitting">{{ t('common.save') }}</Button>
    </div>
  </form>
</template>
