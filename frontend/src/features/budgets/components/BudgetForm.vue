<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { z } from 'zod'
import { useCategories } from '@/shared/api/queries/categories'
import { isApiError } from '@/shared/api/problem'
import type { BudgetResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import MoneyInput from '@/shared/ui/MoneyInput.vue'
import SelectField from '@/shared/ui/SelectField.vue'
import { useCreateBudget, useUpdateBudget } from '../api/budgets-api'

const props = defineProps<{
  /** Budget being edited (only its limit changes); absent when creating. */
  budget?: BudgetResponse
  /** Categories that already have a budget; they are not offered when creating. */
  budgetedCategoryIds?: string[]
}>()
const emit = defineEmits<{ done: [] }>()

const { t } = useI18n()
const create = useCreateBudget()
const update = useUpdateBudget()

const schema = computed(() =>
  toTypedSchema(
    z.object({
      categoryId: z.string().min(1, t('validation.NotNull')),
      monthlyLimitCents: z
        .number()
        .int()
        .nullable()
        .refine((v): v is number => v !== null && v > 0, {
          message: t('budgets.form.limitPositive'),
        }),
    }),
  ),
)

const budget = props.budget
const { defineField, handleSubmit, errors, isSubmitting, setFieldError } = useForm({
  validationSchema: schema,
  initialValues: {
    categoryId: budget?.category.id ?? '',
    monthlyLimitCents: (budget?.monthlyLimitCents ?? null) as number | null,
  },
})
const [categoryId] = defineField('categoryId')
const [limit] = defineField('monthlyLimitCents')
const formError = ref<string | null>(null)

const categories = useCategories('EXPENSE')
/** Active expense categories without a budget yet. */
const options = computed(() =>
  (categories.data.value ?? [])
    .filter((c) => !c.archived && !props.budgetedCategoryIds?.includes(c.id))
    .map((c) => ({ id: c.id, label: c.name })),
)

const CATEGORY_CODES = ['BUDGET_EXISTS', 'BUDGET_CATEGORY_INVALID', 'CATEGORY_ARCHIVED']

const submit = handleSubmit(async (values) => {
  formError.value = null
  try {
    if (budget) {
      await update.mutateAsync({
        id: budget.id,
        body: { monthlyLimitCents: values.monthlyLimitCents },
      })
    } else {
      await create.mutateAsync({
        categoryId: values.categoryId,
        monthlyLimitCents: values.monthlyLimitCents,
      })
    }
  } catch (e) {
    const shown = applyFieldErrors(e, (field, message) => {
      if (field === 'categoryId' || field === 'monthlyLimitCents') setFieldError(field, message)
      else formError.value = message
    })
    if (shown) return
    if (isApiError(e) && CATEGORY_CODES.includes(e.code)) {
      setFieldError('categoryId', errorMessage(e))
    } else {
      formError.value = errorMessage(e)
    }
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

    <p v-if="budget" class="grid gap-1 text-sm">
      <span class="font-medium text-foreground">{{ t('budgets.form.category') }}</span>
      <span class="text-muted-foreground">{{ budget.category.name }}</span>
    </p>
    <template v-else>
      <SelectField
        v-model="categoryId"
        :label="t('budgets.form.category')"
        :options="options"
        :placeholder="t('budgets.form.choose')"
        :error="errors.categoryId"
      />
      <p v-if="categories.isSuccess.value && !options.length" class="text-sm text-muted-foreground">
        {{ t('budgets.form.noCategories') }}
      </p>
    </template>

    <MoneyInput
      v-model="limit"
      :label="t('budgets.form.limit')"
      :error="errors.monthlyLimitCents"
    />

    <div class="flex justify-end pt-2">
      <Button type="submit" :disabled="isSubmitting">{{ t('common.save') }}</Button>
    </div>
  </form>
</template>
