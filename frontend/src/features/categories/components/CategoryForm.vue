<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { z } from 'zod'
import { useCreateCategory, useUpdateCategory } from '@/shared/api/queries/categories'
import { isApiError } from '@/shared/api/problem'
import type { CategoryKind, CategoryResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import { Label } from '@/shared/ui/label'
import { applyFieldErrors } from '@/shared/ui/FormErrors'
import TextField from '@/shared/ui/TextField.vue'
import ColorPicker from './ColorPicker.vue'
import IconPicker from './IconPicker.vue'

const props = defineProps<{
  /** Category being edited; absent when creating. */
  category?: CategoryResponse
  /** Kind preselected when creating (the active tab). */
  defaultKind: CategoryKind
}>()
const emit = defineEmits<{ done: [] }>()

const { t } = useI18n()
const create = useCreateCategory()
const update = useUpdateCategory()

const KINDS = ['EXPENSE', 'INCOME'] as const

const schema = computed(() =>
  toTypedSchema(
    z.object({
      name: z.string().trim().min(1, t('validation.NotBlank')).max(60, t('validation.Size')),
      kind: z.enum(KINDS),
      icon: z.string().min(1, t('validation.NotBlank')),
      color: z.string().min(1, t('validation.NotBlank')),
    }),
  ),
)
const { defineField, handleSubmit, errors, isSubmitting, setFieldError } = useForm({
  validationSchema: schema,
  initialValues: {
    name: props.category?.name ?? '',
    kind: props.category?.kind ?? props.defaultKind,
    icon: props.category?.icon ?? 'ellipsis',
    color: props.category?.color ?? '',
  },
})
const [name, nameAttrs] = defineField('name')
const [kind, kindAttrs] = defineField('kind')
const [icon] = defineField('icon')
const [color] = defineField('color')
const formError = ref<string | null>(null)

const FIELDS = ['name', 'kind', 'icon', 'color'] as const
function showFieldError(field: string, message: string) {
  const known = FIELDS.find((f) => f === field)
  if (known) setFieldError(known, message)
  else formError.value = message
}

const submit = handleSubmit(async (values) => {
  formError.value = null
  try {
    if (props.category) {
      // Full PUT; the kind is immutable.
      await update.mutateAsync({
        id: props.category.id,
        body: { name: values.name, color: values.color, icon: values.icon },
      })
    } else {
      await create.mutateAsync(values)
    }
  } catch (e) {
    if (applyFieldErrors(e, showFieldError)) return
    // CATEGORY_NAME_TAKEN belongs to the name field
    if (isApiError(e) && e.code === 'CATEGORY_NAME_TAKEN') setFieldError('name', errorMessage(e))
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
    <TextField
      v-model="name"
      v-bind="nameAttrs"
      :label="t('categories.form.name')"
      :error="errors.name"
      autocomplete="off"
    />
    <div v-if="!category" class="grid gap-2">
      <Label for="category-kind">{{ t('categories.form.kind') }}</Label>
      <select
        id="category-kind"
        v-model="kind"
        v-bind="kindAttrs"
        class="h-9 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50"
      >
        <option v-for="k in KINDS" :key="k" :value="k">{{ t(`categories.kind.${k}`) }}</option>
      </select>
    </div>
    <IconPicker v-model="icon" :label="t('categories.form.icon')" />
    <ColorPicker v-model="color" :label="t('categories.form.color')" />
    <p v-if="errors.color" class="text-sm text-destructive">{{ errors.color }}</p>
    <Button type="submit" :disabled="isSubmitting">{{ t('common.save') }}</Button>
  </form>
</template>
