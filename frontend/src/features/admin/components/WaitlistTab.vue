<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { toast } from 'vue-sonner'
import { useRoute, useRouter } from 'vue-router'
import type { WaitlistEntryResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { formatInstant } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { Badge } from '@/shared/ui/badge'
import { Button } from '@/shared/ui/button'
import ConfirmDialog from '@/shared/ui/ConfirmDialog.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import SelectField from '@/shared/ui/SelectField.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import { useDecideWaitlistEntry, useWaitlist, type WaitlistStatus } from '../api/admin-api'

const STATUSES: WaitlistStatus[] = ['PENDING', 'APPROVED', 'REJECTED']

const { t, locale } = useI18n()
const route = useRoute()
const router = useRouter()

// The URL is the source of truth; `page` is 1-based there and zero-based in the API.
const status = computed<WaitlistStatus>(() => {
  const raw = route.query.status
  return STATUSES.find((s) => s === raw) ?? 'PENDING'
})
const pageIndex = computed(() => {
  const n = Number(route.query.page)
  return Number.isInteger(n) && n >= 1 ? n - 1 : 0
})

function replaceQuery(patch: Record<string, string | undefined>) {
  return router.replace({ query: { ...route.query, ...patch } })
}
const setStatus = (value: string) => replaceQuery({ status: value, page: undefined })
const goTo = (index: number) => replaceQuery({ page: index === 0 ? undefined : String(index + 1) })

const waitlist = useWaitlist(status, pageIndex)
const decide = useDecideWaitlistEntry()
const statusOptions = computed(() =>
  STATUSES.map((s) => ({ id: s, label: t(`admin.status.${s}`) })),
)
const totalPages = computed(() => waitlist.data.value?.totalPages ?? 1)

const pendingId = ref<string | null>(null)
const rejecting = ref<WaitlistEntryResponse | null>(null)
const rejectOpen = computed({
  get: () => rejecting.value !== null,
  set: (open) => {
    if (!open) rejecting.value = null
  },
})

async function run(entry: WaitlistEntryResponse, action: 'approve' | 'reject') {
  pendingId.value = entry.id
  try {
    await decide.mutateAsync({ id: entry.id, action })
    const key =
      action === 'reject' ? 'rejected' : entry.status === 'APPROVED' ? 'resent' : 'approved'
    toast.success(t(`admin.waitlist.${key}`, { email: entry.email }))
  } catch (e) {
    toast.error(errorMessage(e))
  } finally {
    pendingId.value = null
  }
}

function askReject(entry: WaitlistEntryResponse) {
  // An approved entry already has an emailed code, which rejecting revokes.
  if (entry.status === 'APPROVED') rejecting.value = entry
  else void run(entry, 'reject')
}

const badgeVariant = (s: WaitlistStatus) =>
  s === 'APPROVED' ? 'default' : s === 'REJECTED' ? 'destructive' : 'secondary'
const when = (iso: string) => formatInstant(iso, locale.value as Locale)
</script>

<template>
  <div class="grid gap-4">
    <div class="max-w-xs">
      <SelectField
        :model-value="status"
        :label="t('admin.status.label')"
        :options="statusOptions"
        @update:model-value="setStatus"
      />
    </div>

    <div v-if="waitlist.isError.value" role="alert" class="text-sm text-destructive">
      {{ errorMessage(waitlist.error.value) }}
    </div>
    <div v-else-if="waitlist.isPending.value" class="grid gap-2">
      <Skeleton v-for="n in 3" :key="n" class="h-16 w-full" />
    </div>
    <EmptyState
      v-else-if="!waitlist.data.value?.content.length"
      :title="t('admin.waitlist.empty')"
    />
    <ul v-else class="grid gap-2">
      <li
        v-for="entry in waitlist.data.value.content"
        :key="entry.id"
        class="flex flex-col gap-3 rounded-lg border border-border bg-card p-4 md:flex-row md:items-center md:justify-between"
      >
        <div class="flex min-w-0 flex-col gap-1">
          <span class="flex flex-wrap items-center gap-2">
            <span class="truncate font-medium text-foreground">{{ entry.email }}</span>
            <Badge :variant="badgeVariant(entry.status)">{{
              t(`admin.status.${entry.status}`)
            }}</Badge>
          </span>
          <span class="text-sm text-muted-foreground">
            {{ t('admin.waitlist.locale') }}: {{ entry.locale }} ·
            {{ t('admin.waitlist.requested', { date: when(entry.createdAt) }) }}
          </span>
        </div>
        <div class="flex flex-wrap gap-2">
          <Button
            v-if="entry.status !== 'REJECTED'"
            size="sm"
            :variant="entry.status === 'APPROVED' ? 'outline' : 'default'"
            :disabled="pendingId === entry.id"
            @click="run(entry, 'approve')"
          >
            {{
              entry.status === 'APPROVED' ? t('admin.waitlist.resend') : t('admin.waitlist.approve')
            }}
          </Button>
          <Button
            v-else
            size="sm"
            :disabled="pendingId === entry.id"
            @click="run(entry, 'approve')"
          >
            {{ t('admin.waitlist.approve') }}
          </Button>
          <Button
            v-if="entry.status !== 'REJECTED'"
            size="sm"
            variant="outline"
            :disabled="pendingId === entry.id"
            @click="askReject(entry)"
          >
            {{ t('admin.waitlist.reject') }}
          </Button>
        </div>
      </li>
    </ul>

    <nav
      v-if="totalPages > 1"
      :aria-label="t('admin.waitlist.pager')"
      class="flex items-center justify-between gap-2"
    >
      <Button variant="outline" size="sm" :disabled="pageIndex === 0" @click="goTo(pageIndex - 1)">
        {{ t('admin.waitlist.previous') }}
      </Button>
      <span class="text-sm text-muted-foreground">
        {{ t('admin.waitlist.page', { page: pageIndex + 1, total: totalPages }) }}
      </span>
      <Button
        variant="outline"
        size="sm"
        :disabled="pageIndex + 1 >= totalPages"
        @click="goTo(pageIndex + 1)"
      >
        {{ t('admin.waitlist.next') }}
      </Button>
    </nav>

    <ConfirmDialog
      v-model:open="rejectOpen"
      destructive
      :title="t('admin.waitlist.rejectTitle')"
      :message="t('admin.waitlist.rejectMessage', { email: rejecting?.email ?? '' })"
      :confirm-label="t('admin.waitlist.reject')"
      @confirm="rejecting && run(rejecting, 'reject')"
    />
  </div>
</template>
