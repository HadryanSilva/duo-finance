<script setup lang="ts">
import { Monitor, Moon, Sun } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { getTheme, setTheme, type Theme } from '@/shared/lib/theme'
import { Button } from '@/shared/ui/button'

const { t } = useI18n()
const theme = ref<Theme>(getTheme())
const NEXT: Record<Theme, Theme> = { light: 'dark', dark: 'system', system: 'light' }
const ICON = { light: Sun, dark: Moon, system: Monitor }
const icon = computed(() => ICON[theme.value])

function cycle() {
  theme.value = NEXT[theme.value]
  setTheme(theme.value)
}
</script>

<template>
  <Button
    variant="ghost"
    size="icon"
    :aria-label="t('theme.toggle', { theme: t(`theme.${theme}`) })"
    @click="cycle"
  >
    <component :is="icon" aria-hidden="true" />
  </Button>
</template>
