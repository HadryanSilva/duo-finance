<script setup lang="ts">
import { useId } from 'vue'
import { Label } from '@/shared/ui/label'
import NativeSelect from '@/shared/ui/NativeSelect.vue'

defineOptions({ inheritAttrs: false })

defineProps<{
  label: string
  options: { id: string; label: string }[]
  error?: string
  /** Disabled empty first option ("Select"). */
  placeholder?: string
  /** Selectable empty first option (for optional fields). */
  emptyLabel?: string
}>()
const model = defineModel<string>({ default: '' })

const id = useId()
</script>

<template>
  <div class="grid gap-2">
    <Label :for="id">{{ label }}</Label>
    <NativeSelect
      :id="id"
      v-model="model"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="error ? `${id}-error` : undefined"
      v-bind="$attrs"
    >
      <option v-if="placeholder" value="" disabled>{{ placeholder }}</option>
      <option v-else-if="emptyLabel !== undefined" value="">{{ emptyLabel }}</option>
      <option v-for="o in options" :key="o.id" :value="o.id">{{ o.label }}</option>
    </NativeSelect>
    <p v-if="error" :id="`${id}-error`" class="text-sm text-destructive">{{ error }}</p>
  </div>
</template>
