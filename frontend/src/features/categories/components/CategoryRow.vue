<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { CategoryResponse } from '@/shared/api/types'
import ArchivedBadge from '@/shared/ui/ArchivedBadge.vue'
import CategoryIcon from '@/shared/ui/CategoryIcon.vue'
import { swatchInkClass } from '../lib/contrast'
import RowActions from '@/shared/ui/RowActions.vue'

const props = defineProps<{ category: CategoryResponse }>()
const emit = defineEmits<{ edit: []; archive: []; unarchive: []; remove: [] }>()

const { t } = useI18n()
const label = (action: 'edit' | 'archive' | 'unarchive' | 'delete') =>
  t(`categories.actions.${action}`, { name: props.category.name })
</script>

<template>
  <li class="flex items-center gap-3 rounded-lg border border-border bg-card p-4">
    <!-- the color comes from the API as data, not from the design tokens -->
    <span
      class="flex size-9 shrink-0 items-center justify-center rounded-full"
      :class="swatchInkClass(category.color)"
      :style="{ backgroundColor: category.color }"
    >
      <CategoryIcon :name="category.icon" />
    </span>
    <div class="flex min-w-0 flex-1 flex-wrap items-center gap-2">
      <span class="truncate font-medium text-foreground">{{ category.name }}</span>
      <ArchivedBadge v-if="category.archived" />
    </div>
    <RowActions
      :archived="category.archived"
      :labels="{
        edit: label('edit'),
        archive: label('archive'),
        unarchive: label('unarchive'),
        remove: label('delete'),
      }"
      @edit="emit('edit')"
      @archive="emit('archive')"
      @unarchive="emit('unarchive')"
      @remove="emit('remove')"
    />
  </li>
</template>
