<script setup lang="ts">
import { Archive, ArchiveArrowUp, Pencil, Trash } from '@lucide/vue'
import { Button } from '@/shared/ui/button'

/** Edit, archive/unarchive and delete icon buttons; labels already carry the item name. */
defineProps<{
  archived: boolean
  labels: { edit: string; archive: string; unarchive: string; remove: string }
}>()
const emit = defineEmits<{ edit: []; archive: []; unarchive: []; remove: [] }>()
</script>

<template>
  <div class="flex items-center">
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
