<script setup lang="ts">
import { ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { buttonVariants } from '@/shared/ui/button'
import { Label } from '@/shared/ui/label'
import { cn } from '@/shared/lib/utils'

/**
 * File picker with the app's look: the native input stays (focusable, labelled, opens the picker),
 * shown as an outline button plus the chosen file's name.
 */
defineProps<{ label: string; accept?: string; disabled?: boolean }>()
const model = defineModel<File | null>({ required: true })

const { t } = useI18n()
const id = useId()
const input = ref<HTMLInputElement | null>(null)

function onChange(event: Event) {
  model.value = (event.target as HTMLInputElement).files?.[0] ?? null
}

// A reset by the parent clears the input too, so picking the same file again still fires change.
watch(model, (file) => {
  if (!file && input.value) input.value.value = ''
})
</script>

<template>
  <div class="grid gap-2">
    <Label :for="id">{{ label }}</Label>
    <div class="flex min-w-0 items-center gap-3">
      <input
        :id="id"
        ref="input"
        type="file"
        class="peer sr-only"
        :accept="accept"
        :disabled="disabled"
        @change="onChange"
      />
      <label
        :for="id"
        aria-hidden="true"
        :class="
          cn(
            buttonVariants({ variant: 'outline', size: 'sm' }),
            'cursor-pointer peer-focus-visible:border-ring peer-focus-visible:ring-3 peer-focus-visible:ring-ring/50 peer-disabled:pointer-events-none peer-disabled:opacity-50',
          )
        "
      >
        {{ t('common.file.choose') }}
      </label>
      <span class="min-w-0 truncate text-sm text-muted-foreground">
        {{ model?.name ?? t('common.file.none') }}
      </span>
    </div>
  </div>
</template>
