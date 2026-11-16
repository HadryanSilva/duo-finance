<script setup lang="ts">
import { computed, ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCategories } from '@/shared/api/queries/categories'
import { useGoalSettings, useUpdateGoalSettings } from '@/shared/api/queries/goals'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Label } from '@/shared/ui/label'
import { nativeSelectClass } from '@/shared/ui/native-select'

const { t } = useI18n()
const id = useId()
const settings = useGoalSettings()
const categories = useCategories('INCOME')
const save = useUpdateGoalSettings()
const error = ref<string | null>(null)

const saved = computed(() => settings.data.value?.salaryCategory ?? null)
const options = computed(() => {
  const active = (categories.data.value ?? []).filter((c) => !c.archived)
  const list = active.map((c) => ({ id: c.id, label: c.name }))
  // An archived saved category is not in the active list: keep showing it, marked.
  if (saved.value?.archived && !active.some((c) => c.id === saved.value?.id)) {
    list.unshift({
      id: saved.value.id,
      label: t('goals.salary.archivedOption', { name: saved.value.name }),
    })
  }
  return list
})

async function onChange(event: Event) {
  const value = (event.target as HTMLSelectElement).value
  if (!value) return
  error.value = null
  try {
    await save.mutateAsync({ salaryCategoryId: value })
  } catch (e) {
    error.value = errorMessage(e)
  }
}
</script>

<template>
  <div class="mb-4 grid gap-2 rounded-lg border border-border bg-card p-4 sm:max-w-md">
    <Label :for="id">{{ t('goals.salary.label') }}</Label>
    <select
      :id="id"
      :class="nativeSelectClass"
      :value="saved?.id ?? ''"
      :disabled="save.isPending.value"
      @change="onChange"
    >
      <option value="" disabled>{{ t('goals.salary.choose') }}</option>
      <option v-for="o in options" :key="o.id" :value="o.id">{{ o.label }}</option>
    </select>
    <p v-if="saved?.archived" role="status" class="text-sm text-warning">
      {{ t('goals.salary.archivedWarning') }}
    </p>
    <p v-if="error" role="alert" class="text-sm text-destructive">{{ error }}</p>
  </div>
</template>
