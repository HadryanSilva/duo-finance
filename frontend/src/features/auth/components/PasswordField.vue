<script setup lang="ts">
import { Eye, EyeOff } from '@lucide/vue'
import { ref, useId } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/shared/ui/button'
import { Input } from '@/shared/ui/input'
import { Label } from '@/shared/ui/label'

defineOptions({ inheritAttrs: false })

defineProps<{ label: string; error?: string; hint?: string }>()
const model = defineModel<string>()

const { t } = useI18n()
const id = useId()
const visible = ref(false)
</script>

<template>
  <div class="grid gap-2">
    <Label :for="id">{{ label }}</Label>
    <div class="relative">
      <Input
        :id="id"
        v-model="model"
        :type="visible ? 'text' : 'password'"
        class="pr-10"
        :aria-invalid="error ? 'true' : undefined"
        :aria-describedby="error ? `${id}-error` : hint ? `${id}-hint` : undefined"
        v-bind="$attrs"
      />
      <Button
        type="button"
        variant="ghost"
        size="icon-sm"
        class="absolute top-0.5 right-0.5"
        :aria-label="visible ? t('auth.hidePassword') : t('auth.showPassword')"
        :aria-pressed="visible"
        @click="visible = !visible"
      >
        <EyeOff v-if="visible" aria-hidden="true" />
        <Eye v-else aria-hidden="true" />
      </Button>
    </div>
    <p v-if="error" :id="`${id}-error`" class="text-sm text-destructive">{{ error }}</p>
    <p v-else-if="hint" :id="`${id}-hint`" class="text-sm text-muted-foreground">{{ hint }}</p>
  </div>
</template>
