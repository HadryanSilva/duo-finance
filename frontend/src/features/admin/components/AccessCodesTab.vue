<script setup lang="ts">
import { Plus } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import type { AccessCodeResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { formatInstant } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { Badge } from '@/shared/ui/badge'
import { Button } from '@/shared/ui/button'
import ConfirmDialog from '@/shared/ui/ConfirmDialog.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import SidePanel from '@/shared/ui/SidePanel.vue'
import { useAccessCodes, useRevokeAccessCode } from '../api/admin-api'
import { accessCodeStatus, type AccessCodeStatus } from '../lib/access-code-status'
import CreateAccessCodeForm from './CreateAccessCodeForm.vue'
import PlainCodeDialog from './PlainCodeDialog.vue'

const { t, locale } = useI18n()
const codes = useAccessCodes()
const revoke = useRevokeAccessCode()

const formOpen = ref(false)
// The only place the plain code lives; nulled on close. The create mutation lives in the form,
// which unmounts with the panel, and its gcTime is 0, so the response is not kept either.
const plainCode = ref<string | null>(null)
const revoking = ref<AccessCodeResponse | null>(null)
const revokeOpen = computed({
  get: () => revoking.value !== null,
  set: (open) => {
    if (!open) revoking.value = null
  },
})

function onCreated(result: { plainCode: string }) {
  formOpen.value = false
  plainCode.value = result.plainCode
}

function closePlainCode() {
  plainCode.value = null
}

async function doRevoke() {
  const target = revoking.value
  if (!target) return
  try {
    await revoke.mutateAsync(target.id)
    toast.success(t('admin.codes.revoked'))
  } catch (e) {
    toast.error(errorMessage(e))
  }
}

// Re-evaluated whenever the list is refetched.
const now = computed(() => {
  void codes.dataUpdatedAt.value
  return new Date()
})
const statusOf = (c: AccessCodeResponse): AccessCodeStatus => accessCodeStatus(c, now.value)
const variant = (s: AccessCodeStatus) =>
  s === 'ACTIVE' ? 'default' : s === 'REVOKED' ? 'destructive' : 'secondary'
const when = (iso: string) => formatInstant(iso, locale.value as Locale)
</script>

<template>
  <div class="grid gap-4">
    <div class="flex justify-end">
      <Button @click="formOpen = true">
        <Plus class="size-4" aria-hidden="true" />
        {{ t('admin.codes.generate') }}
      </Button>
    </div>

    <div v-if="codes.isError.value" role="alert" class="text-sm text-destructive">
      {{ errorMessage(codes.error.value) }}
    </div>
    <div v-else-if="codes.isPending.value" class="grid gap-2">
      <Skeleton v-for="n in 3" :key="n" class="h-16 w-full" />
    </div>
    <EmptyState v-else-if="!codes.data.value?.length" :title="t('admin.codes.empty')" />
    <ul v-else class="grid gap-2">
      <li
        v-for="c in codes.data.value"
        :key="c.id"
        class="flex flex-col gap-3 rounded-lg border border-border bg-card p-4 md:flex-row md:items-center md:justify-between"
      >
        <div class="flex min-w-0 flex-col gap-1">
          <span class="flex flex-wrap items-center gap-2">
            <Badge :variant="variant(statusOf(c))">{{
              t(`admin.codes.status.${statusOf(c)}`)
            }}</Badge>
            <span v-if="c.note" class="truncate font-medium text-foreground">{{ c.note }}</span>
          </span>
          <span class="flex flex-wrap gap-x-3 text-sm text-muted-foreground">
            <span>{{ c.email ?? t('admin.codes.anyone') }}</span>
            <span>
              {{ t('admin.codes.uses') }}:
              <span class="tabular-nums">{{ c.usedCount }}/{{ c.maxUses }}</span>
            </span>
            <span>
              {{ t('admin.codes.expires') }}:
              {{ c.expiresAt ? when(c.expiresAt) : t('admin.codes.never') }}
            </span>
          </span>
        </div>
        <div v-if="statusOf(c) === 'ACTIVE'">
          <Button size="sm" variant="outline" @click="revoking = c">
            {{ t('admin.codes.revoke') }}
          </Button>
        </div>
      </li>
    </ul>

    <SidePanel v-model:open="formOpen" :title="t('admin.form.title')">
      <CreateAccessCodeForm v-if="formOpen" @created="onCreated" />
    </SidePanel>
    <PlainCodeDialog :code="plainCode" @close="closePlainCode" />
    <ConfirmDialog
      v-model:open="revokeOpen"
      destructive
      :title="t('admin.codes.revokeTitle')"
      :message="t('admin.codes.revokeMessage')"
      :confirm-label="t('admin.codes.revoke')"
      @confirm="doRevoke"
    />
  </div>
</template>
