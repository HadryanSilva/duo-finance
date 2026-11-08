<script setup lang="ts">
import { computed } from 'vue'
import { categoryIcon } from '@/shared/lib/category-icons'

const props = defineProps<{ name: string }>()
const icon = computed(() => categoryIcon(props.name))
</script>

<template>
  <component :is="icon" aria-hidden="true" class="size-4" />
</template>
