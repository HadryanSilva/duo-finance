<script setup lang="ts">
import { computed, ref, useId, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { formatMoney, parseMoney, type Locale } from '@/shared/lib/money'
import { Input } from '@/shared/ui/input'
import { Label } from '@/shared/ui/label'

defineOptions({ inheritAttrs: false })

const props = defineProps<{ label: string; allowNegative?: boolean; error?: string }>()
/** Amount in cents; null while the text is empty or not a valid amount. */
const model = defineModel<number | null>({ default: null })

const { locale } = useI18n()
const loc = computed(() => locale.value as Locale)
const id = useId()

const DECIMAL: Record<Locale, string> = { 'pt-BR': ',', en: '.' }

/** Raw editable text for an amount: no symbol, no grouping. */
function rawText(cents: number): string {
  const abs = Math.abs(cents)
  const frac = String(abs % 100).padStart(2, '0')
  return `${cents < 0 ? '-' : ''}${Math.floor(abs / 100)}${DECIMAL[loc.value]}${frac}`
}

const focused = ref(false)
const invalid = ref(false)
const text = ref(model.value === null ? '' : formatMoney(model.value, loc.value))

function parse(value: string): number | null {
  return parseMoney(value, loc.value, { allowNegative: props.allowNegative })
}

function onInput(value: string | number) {
  text.value = String(value)
  const cents = parse(text.value)
  invalid.value = cents === null && text.value.trim() !== ''
  model.value = cents
}

function onFocus() {
  focused.value = true
  if (model.value !== null) text.value = rawText(model.value)
}

function onBlur() {
  focused.value = false
  if (model.value !== null) text.value = formatMoney(model.value, loc.value)
}

// Outside changes (form reset, loaded data, locale switch) refresh the text unless the user is typing.
watch([model, loc], ([value]) => {
  if (focused.value && value === parse(text.value)) return
  text.value = value === null ? (invalid.value ? text.value : '') : formatMoney(value, loc.value)
})
</script>

<template>
  <div class="grid gap-2">
    <Label :for="id">{{ label }}</Label>
    <Input
      :id="id"
      :model-value="text"
      inputmode="decimal"
      autocomplete="off"
      :aria-invalid="invalid || error ? 'true' : undefined"
      :aria-describedby="error ? `${id}-error` : undefined"
      class="tabular-nums"
      v-bind="$attrs"
      @update:model-value="onInput"
      @focus="onFocus"
      @blur="onBlur"
    />
    <p v-if="error" :id="`${id}-error`" class="text-sm text-destructive">{{ error }}</p>
  </div>
</template>
