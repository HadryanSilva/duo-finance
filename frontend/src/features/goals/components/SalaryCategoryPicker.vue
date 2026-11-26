<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useCategories } from '@/shared/api/queries/categories'
import { useGoalSettings, useUpdateGoalSettings } from '@/shared/api/queries/goals'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Label } from '@/shared/ui/label'
import NativeSelect from '@/shared/ui/NativeSelect.vue'

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

// What the select shows: the saved category, or the one being saved.
const picked = ref('')
watch(
  () => saved.value?.id,
  (id) => (picked.value = id ?? ''),
  { immediate: true },
)

async function onChange(value: string | undefined) {
  if (!value || value === saved.value?.id) return
  error.value = null
  try {
    await save.mutateAsync({ salaryCategoryId: value })
  } catch (e) {
    error.value = errorMessage(e)
    // The choice was not saved: show the saved one again rather than the one just picked.
    picked.value = saved.value?.id ?? ''
  }
}
</script>

<template>
  <div class="mb-4 grid gap-2 rounded-lg border border-border bg-card p-4 sm:max-w-md">
    <Label :for="id">{{ t('goals.salary.label') }}</Label>
    <NativeSelect
      :id="id"
      v-model="picked"
      :disabled="save.isPending.value"
      @update:model-value="onChange"
    >
      <option value="" disabled>{{ t('goals.salary.choose') }}</option>
      <option v-for="o in options" :key="o.id" :value="o.id">{{ o.label }}</option>
    </NativeSelect>
    <p v-if="saved?.archived" role="status" class="text-sm text-warning">
      {{ t('goals.salary.archivedWarning') }}
    </p>
    <p v-if="error" role="alert" class="text-sm text-destructive">{{ error }}</p>
  </div>
</template>
