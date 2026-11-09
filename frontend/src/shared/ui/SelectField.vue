<script setup lang="ts">
import { useId } from 'vue'
import { Label } from '@/shared/ui/label'

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
const SELECT =
  'h-9 w-full rounded-md border border-input bg-background px-3 text-sm outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 aria-invalid:border-destructive'
</script>

<template>
  <div class="grid gap-2">
    <Label :for="id">{{ label }}</Label>
    <select
      :id="id"
      v-model="model"
      :class="SELECT"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="error ? `${id}-error` : undefined"
      v-bind="$attrs"
    >
      <option v-if="placeholder" value="" disabled>{{ placeholder }}</option>
      <option v-else-if="emptyLabel !== undefined" value="">{{ emptyLabel }}</option>
      <option v-for="o in options" :key="o.id" :value="o.id">{{ o.label }}</option>
    </select>
    <p v-if="error" :id="`${id}-error`" class="text-sm text-destructive">{{ error }}</p>
  </div>
</template>
