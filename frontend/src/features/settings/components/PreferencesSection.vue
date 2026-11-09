<script setup lang="ts">
import { ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { useUpdateMe } from '@/shared/api/queries/me'
import { errorMessage } from '@/shared/i18n/error-codes'
import { getTheme, setTheme, type Theme } from '@/shared/lib/theme'
import { useSession } from '@/shared/session/session-store'
import SelectField from '@/shared/ui/SelectField.vue'
import SettingsSection from './SettingsSection.vue'

const { t, locale } = useI18n()
const session = useSession()
const update = useUpdateMe()

const LANGUAGES = [
  { id: 'pt-BR', label: 'Português (Brasil)' },
  { id: 'en', label: 'English' },
]
const THEMES: Theme[] = ['light', 'dark', 'system']
const themeOptions = () => THEMES.map((id) => ({ id, label: t(`theme.${id}`) }))

const theme = ref<Theme>(getTheme())
const languageError = ref<string | undefined>()

async function changeLanguage(next: string) {
  languageError.value = undefined
  try {
    // Saved first, then the session applies the locale: a failed save leaves the UI as it was.
    session.updateUser(await update.mutateAsync({ locale: next }))
  } catch (e) {
    languageError.value = errorMessage(e)
    return
  }
  toast.success(t('settings.preferences.languageSaved'))
}

function changeTheme(next: string) {
  const picked = THEMES.find((x) => x === next)
  if (!picked) return
  theme.value = picked
  setTheme(picked)
}
</script>

<template>
  <SettingsSection :title="t('settings.preferences.title')">
    <div class="grid gap-4 sm:grid-cols-2">
      <SelectField
        :label="t('settings.preferences.language')"
        :options="LANGUAGES"
        :model-value="locale"
        :error="languageError"
        @update:model-value="changeLanguage"
      />
      <SelectField
        :label="t('settings.preferences.theme')"
        :options="themeOptions()"
        :model-value="theme"
        @update:model-value="changeTheme"
      />
    </div>
  </SettingsSection>
</template>
