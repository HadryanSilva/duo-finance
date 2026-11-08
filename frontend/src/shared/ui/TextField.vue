<script setup lang="ts">
import { useId } from 'vue'
import { Input } from '@/shared/ui/input'
import { Label } from '@/shared/ui/label'

defineOptions({ inheritAttrs: false })

defineProps<{ label: string; error?: string; type?: string; hint?: string }>()
const model = defineModel<string>()

const id = useId()
</script>

<template>
  <div class="grid gap-2">
    <Label :for="id">{{ label }}</Label>
    <Input
      :id="id"
      v-model="model"
      :type="type ?? 'text'"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="error ? `${id}-error` : hint ? `${id}-hint` : undefined"
      v-bind="$attrs"
    />
    <p v-if="error" :id="`${id}-error`" class="text-sm text-destructive">{{ error }}</p>
    <p v-else-if="hint" :id="`${id}-hint`" class="text-sm text-muted-foreground">{{ hint }}</p>
  </div>
</template>
