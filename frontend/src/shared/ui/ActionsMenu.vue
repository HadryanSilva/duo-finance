<script setup lang="ts">
import { Ellipsis } from '@lucide/vue'
import type { Component } from 'vue'
import { Button } from '@/shared/ui/button'
import {
  DropdownMenu,
  DropdownMenuContent,
  DropdownMenuItem,
  DropdownMenuTrigger,
} from '@/shared/ui/dropdown-menu'

export interface ActionItem {
  key: string
  label: string
  icon: Component
  destructive?: boolean
}

/** A "⋯" button opening the row's actions: the mobile form of a row's icon buttons. */
defineProps<{ label: string; items: readonly ActionItem[] }>()
const emit = defineEmits<{ select: [key: string] }>()
</script>

<template>
  <DropdownMenu>
    <DropdownMenuTrigger as-child>
      <Button variant="ghost" size="icon" :aria-label="label">
        <Ellipsis class="size-4" aria-hidden="true" />
      </Button>
    </DropdownMenuTrigger>
    <DropdownMenuContent align="end" class="min-w-40">
      <DropdownMenuItem
        v-for="item in items"
        :key="item.key"
        :variant="item.destructive ? 'destructive' : 'default'"
        @select="emit('select', item.key)"
      >
        <component :is="item.icon" aria-hidden="true" />
        {{ item.label }}
      </DropdownMenuItem>
    </DropdownMenuContent>
  </DropdownMenu>
</template>
