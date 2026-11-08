<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { readCategoryPalette } from '../lib/palette'

defineProps<{ label: string }>()
/** Selected color as the hex string the API stores. */
const model = defineModel<string>({ default: '' })

const { t } = useI18n()

// Full class names so Tailwind generates them.
const SWATCH = [
  'bg-cat-1',
  'bg-cat-2',
  'bg-cat-3',
  'bg-cat-4',
  'bg-cat-5',
  'bg-cat-6',
  'bg-cat-7',
  'bg-cat-8',
  'bg-cat-9',
  'bg-cat-10',
  'bg-cat-11',
  'bg-cat-12',
]

const palette = ref<string[]>([])
onMounted(() => {
  palette.value = readCategoryPalette()
  if (!model.value) model.value = palette.value[0] ?? ''
})
</script>

<template>
  <div class="grid gap-2" role="radiogroup" :aria-label="label">
    <span class="text-sm leading-none font-medium select-none" aria-hidden="true">{{ label }}</span>
    <div class="flex flex-wrap gap-2">
      <button
        v-for="(swatch, i) in SWATCH"
        :key="swatch"
        type="button"
        role="radio"
        :aria-checked="!!model && palette[i] === model.toLowerCase()"
        :aria-label="t('categories.form.colorN', { n: i + 1 })"
        class="size-8 rounded-full border border-border outline-none focus-visible:ring-3 focus-visible:ring-ring/50 aria-checked:ring-2 aria-checked:ring-foreground aria-checked:ring-offset-2 aria-checked:ring-offset-background"
        :class="swatch"
        @click="model = palette[i] ?? ''"
      />
    </div>
  </div>
</template>
