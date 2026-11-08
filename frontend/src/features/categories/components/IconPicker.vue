<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { CATEGORY_ICON_NAMES } from '@/shared/lib/category-icons'
import CategoryIcon from '@/shared/ui/CategoryIcon.vue'

defineProps<{ label: string }>()
const model = defineModel<string>({ default: '' })

const { t } = useI18n()
</script>

<template>
  <div class="grid gap-2" role="radiogroup" :aria-label="label">
    <span class="text-sm leading-none font-medium select-none" aria-hidden="true">{{ label }}</span>
    <div class="flex flex-wrap gap-2">
      <button
        v-for="name in CATEGORY_ICON_NAMES"
        :key="name"
        type="button"
        role="radio"
        :aria-checked="model === name"
        :aria-label="t(`categories.icons.${name}`)"
        class="flex size-9 items-center justify-center rounded-md border border-border bg-card text-foreground outline-none hover:bg-accent focus-visible:ring-3 focus-visible:ring-ring/50 aria-checked:border-primary aria-checked:bg-accent aria-checked:ring-2 aria-checked:ring-primary"
        @click="model = name"
      >
        <CategoryIcon :name="name" />
      </button>
    </div>
  </div>
</template>
