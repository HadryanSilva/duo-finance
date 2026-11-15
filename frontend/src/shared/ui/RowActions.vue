<script setup lang="ts">
import { Archive, ArchiveArrowUp, Pencil, Trash } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useIsDesktop } from '@/shared/lib/breakpoints'
import ActionsMenu, { type ActionItem } from '@/shared/ui/ActionsMenu.vue'
import { Button } from '@/shared/ui/button'

/**
 * Edit, archive/unarchive and delete: icon buttons from md up (labels already carry the item
 * name), a "⋯" menu named `menuLabel` below md.
 */
const props = defineProps<{
  archived: boolean
  labels: { edit: string; archive: string; unarchive: string; remove: string }
  menuLabel: string
}>()
const emit = defineEmits<{ edit: []; archive: []; unarchive: []; remove: [] }>()

const { t } = useI18n()
const isDesktop = useIsDesktop()

const items = computed<ActionItem[]>(() => [
  { key: 'edit', label: t('common.edit'), icon: Pencil },
  props.archived
    ? { key: 'unarchive', label: t('common.unarchive'), icon: ArchiveArrowUp }
    : { key: 'archive', label: t('common.archive'), icon: Archive },
  { key: 'remove', label: t('common.delete'), icon: Trash, destructive: true },
])

function onSelect(key: string) {
  if (key === 'edit') emit('edit')
  else if (key === 'archive') emit('archive')
  else if (key === 'unarchive') emit('unarchive')
  else if (key === 'remove') emit('remove')
}
</script>

<template>
  <ActionsMenu v-if="!isDesktop" :label="menuLabel" :items="items" @select="onSelect" />
  <div v-else class="flex items-center">
    <Button variant="ghost" size="icon" :aria-label="labels.edit" @click="emit('edit')">
      <Pencil class="size-4" aria-hidden="true" />
    </Button>
    <Button
      v-if="!archived"
      variant="ghost"
      size="icon"
      :aria-label="labels.archive"
      @click="emit('archive')"
    >
      <Archive class="size-4" aria-hidden="true" />
    </Button>
    <Button
      v-else
      variant="ghost"
      size="icon"
      :aria-label="labels.unarchive"
      @click="emit('unarchive')"
    >
      <ArchiveArrowUp class="size-4" aria-hidden="true" />
    </Button>
    <Button variant="ghost" size="icon" :aria-label="labels.remove" @click="emit('remove')">
      <Trash class="size-4" aria-hidden="true" />
    </Button>
  </div>
</template>
