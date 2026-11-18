<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useAccount, useUpdateAccount } from '@/shared/api/queries/account'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Switch } from '@/shared/ui/switch'

/** Opt-out of the AI category suggestions of the import. Any member can change it. */
const { t } = useI18n()
const account = useAccount()
const update = useUpdateAccount()
const error = ref<string | null>(null)

// While saving, show the requested value; on failure it falls back to the account's own.
const checked = computed(() =>
  update.isPending.value
    ? (update.variables.value?.aiSuggestionsEnabled ?? false)
    : (account.data.value?.aiSuggestionsEnabled ?? false),
)

async function change(value: boolean) {
  error.value = null
  try {
    await update.mutateAsync({ aiSuggestionsEnabled: value })
  } catch (e) {
    error.value = errorMessage(e)
  }
}
</script>

<template>
  <div v-if="account.data.value" class="grid gap-2">
    <div class="flex items-center justify-between gap-3">
      <label for="ai-suggestions" class="text-sm font-medium text-foreground">
        {{ t('settings.couple.aiSuggestions') }}
      </label>
      <Switch
        id="ai-suggestions"
        :model-value="checked"
        :disabled="update.isPending.value"
        aria-describedby="ai-suggestions-hint"
        @update:model-value="change"
      />
    </div>
    <p id="ai-suggestions-hint" class="text-sm text-muted-foreground">
      {{ t('settings.couple.aiSuggestionsHint') }}
    </p>
    <p v-if="error" role="alert" class="text-sm text-destructive">{{ error }}</p>
  </div>
</template>
