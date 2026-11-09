<script setup lang="ts">
import { Check, Copy } from '@lucide/vue'
import { ref, useTemplateRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { Button } from '@/shared/ui/button'
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/shared/ui/dialog'

/** The plain access code is shown while `code` is set; the owner nulls it on `close`. */
const props = defineProps<{ code: string | null }>()
const emit = defineEmits<{ close: [] }>()

const { t } = useI18n()
const token = useTemplateRef<HTMLElement>('token')
const state = ref<'idle' | 'copied' | 'manual'>('idle')

function selectToken() {
  const el = token.value
  if (!el) return
  const range = document.createRange()
  range.selectNodeContents(el)
  const selection = window.getSelection()
  selection?.removeAllRanges()
  selection?.addRange(range)
}

async function copy() {
  try {
    await navigator.clipboard.writeText(props.code ?? '')
    state.value = 'copied'
  } catch {
    selectToken()
    state.value = 'manual'
  }
}

function onOpenChange(open: boolean) {
  if (open) return
  state.value = 'idle'
  emit('close')
}
</script>

<template>
  <Dialog :open="code !== null" @update:open="onOpenChange">
    <!-- Only Escape and the button close it: an outside click would lose the one-time code. -->
    <DialogContent
      :show-close-button="false"
      @pointer-down-outside.prevent
      @interact-outside.prevent
    >
      <DialogHeader>
        <DialogTitle>{{ t('admin.plain.title') }}</DialogTitle>
        <DialogDescription>{{ t('admin.plain.description') }}</DialogDescription>
      </DialogHeader>
      <code
        ref="token"
        class="block break-all rounded-md border border-border bg-muted px-3 py-2 font-mono text-base text-foreground select-all"
        >{{ code }}</code
      >
      <p role="status" class="text-sm text-foreground">
        <template v-if="state === 'copied'">{{ t('admin.plain.copied') }}</template>
        <template v-else-if="state === 'manual'">{{ t('admin.plain.manual') }}</template>
      </p>
      <p class="rounded-md bg-muted p-3 text-sm text-foreground">{{ t('admin.plain.warning') }}</p>
      <DialogFooter>
        <Button variant="outline" @click="copy">
          <component :is="state === 'copied' ? Check : Copy" class="size-4" aria-hidden="true" />
          {{ t('admin.plain.copy') }}
        </Button>
        <Button @click="onOpenChange(false)">{{ t('common.close') }}</Button>
      </DialogFooter>
    </DialogContent>
  </Dialog>
</template>
