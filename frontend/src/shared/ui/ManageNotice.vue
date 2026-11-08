<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { Button } from '@/shared/ui/button'

/** Error banner for archive/delete: offers "Archive" when deleting is blocked, or a link to recurring rules. */
defineProps<{ message: string; canArchive?: boolean; showRecurringLink?: boolean }>()
const emit = defineEmits<{ archive: []; dismiss: [] }>()

const { t } = useI18n()
</script>

<template>
  <div
    role="alert"
    class="mb-4 flex flex-wrap items-center gap-3 rounded-md border border-border bg-card p-3 text-sm text-destructive"
  >
    <p class="min-w-0 flex-1">{{ message }}</p>
    <Button v-if="canArchive" size="sm" variant="outline" @click="emit('archive')">
      {{ t('common.archive') }}
    </Button>
    <RouterLink
      v-if="showRecurringLink"
      to="/recurring"
      class="font-medium text-primary underline underline-offset-4"
    >
      {{ t('common.viewRecurring') }}
    </RouterLink>
    <Button size="sm" variant="ghost" @click="emit('dismiss')">{{ t('common.close') }}</Button>
  </div>
</template>
