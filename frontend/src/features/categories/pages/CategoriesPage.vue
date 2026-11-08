<script setup lang="ts">
import { Plus } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import {
  useArchiveCategory,
  useCategories,
  useDeleteCategory,
  useUnarchiveCategory,
} from '@/shared/api/queries/categories'
import { isApiError } from '@/shared/api/problem'
import type { CategoryKind, CategoryResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { Button } from '@/shared/ui/button'
import { Label } from '@/shared/ui/label'
import { Skeleton } from '@/shared/ui/skeleton'
import { Switch } from '@/shared/ui/switch'
import { Tabs, TabsList, TabsTrigger } from '@/shared/ui/tabs'
import ConfirmDialog from '@/shared/ui/ConfirmDialog.vue'
import EmptyState from '@/shared/ui/EmptyState.vue'
import ManageNotice from '@/shared/ui/ManageNotice.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import SidePanel from '@/shared/ui/SidePanel.vue'
import CategoryForm from '../components/CategoryForm.vue'
import CategoryRow from '../components/CategoryRow.vue'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()

const kind = ref<CategoryKind>('EXPENSE')
const showArchived = computed({
  get: () => route.query.archived === '1',
  set: (value: boolean) =>
    router.replace({ query: { ...route.query, archived: value ? '1' : undefined } }),
})

const categories = useCategories(kind, showArchived)
const archive = useArchiveCategory()
const unarchive = useUnarchiveCategory()
const remove = useDeleteCategory()

// Side panel: null closed, 'new' creating, otherwise the category being edited.
const panel = ref<'new' | CategoryResponse | null>(null)
const panelOpen = computed({
  get: () => panel.value !== null,
  set: (open: boolean) => {
    if (!open) panel.value = null
  },
})

const pendingDelete = ref<CategoryResponse | null>(null)
const deleteOpen = computed({
  get: () => pendingDelete.value !== null,
  set: (open: boolean) => {
    if (!open) pendingDelete.value = null
  },
})

const notice = ref<{
  message: string
  archiveTarget: CategoryResponse | null
  recurringLink: boolean
} | null>(null)

async function run(action: () => Promise<unknown>, target: CategoryResponse) {
  notice.value = null
  try {
    await action()
  } catch (e) {
    const code = isApiError(e) ? e.code : null
    notice.value = {
      message: errorMessage(e),
      archiveTarget: code === 'CATEGORY_IN_USE' ? target : null,
      recurringLink: code === 'IN_USE_BY_ACTIVE_RULE',
    }
  }
}

const onArchive = (c: CategoryResponse) => run(() => archive.mutateAsync(c.id), c)
const onUnarchive = (c: CategoryResponse) => run(() => unarchive.mutateAsync(c.id), c)
const onDelete = (c: CategoryResponse | null) => {
  if (c) return run(() => remove.mutateAsync(c.id), c)
}
</script>

<template>
  <PageHeader :title="t('nav.categories')" :subtitle="t('categories.subtitle')">
    <template #actions>
      <Button @click="panel = 'new'">
        <Plus class="size-4" aria-hidden="true" />
        {{ t('categories.new') }}
      </Button>
    </template>
  </PageHeader>

  <ManageNotice
    v-if="notice"
    :message="notice.message"
    :can-archive="notice.archiveTarget !== null"
    :show-recurring-link="notice.recurringLink"
    @archive="notice.archiveTarget && onArchive(notice.archiveTarget)"
    @dismiss="notice = null"
  />

  <div class="mb-4 flex flex-wrap items-center justify-between gap-4">
    <Tabs v-model="kind">
      <TabsList>
        <TabsTrigger value="EXPENSE">{{ t('categories.kind.EXPENSE') }}</TabsTrigger>
        <TabsTrigger value="INCOME">{{ t('categories.kind.INCOME') }}</TabsTrigger>
      </TabsList>
    </Tabs>
    <div class="flex items-center gap-2">
      <Switch id="show-archived-categories" v-model="showArchived" />
      <Label for="show-archived-categories">{{ t('common.showArchived') }}</Label>
    </div>
  </div>

  <div v-if="categories.isPending.value" class="grid gap-3">
    <Skeleton class="h-16 w-full" />
    <Skeleton class="h-16 w-full" />
  </div>
  <p v-else-if="categories.isError.value" role="alert" class="text-sm text-destructive">
    {{ errorMessage(categories.error.value) }}
  </p>
  <EmptyState
    v-else-if="!categories.data.value?.length"
    :title="t('categories.emptyTitle')"
    :description="t('categories.emptyDescription')"
  >
    <Button @click="panel = 'new'">{{ t('categories.new') }}</Button>
  </EmptyState>
  <ul v-else class="grid gap-3">
    <CategoryRow
      v-for="c in categories.data.value"
      :key="c.id"
      :category="c"
      @edit="panel = c"
      @archive="onArchive(c)"
      @unarchive="onUnarchive(c)"
      @remove="pendingDelete = c"
    />
  </ul>

  <SidePanel
    v-model:open="panelOpen"
    :title="
      panel === 'new' || panel === null ? t('categories.createTitle') : t('categories.editTitle')
    "
  >
    <CategoryForm
      v-if="panel !== null"
      :key="panel === 'new' ? 'new' : panel.id"
      :category="panel === 'new' ? undefined : panel"
      :default-kind="kind"
      @done="panel = null"
    />
  </SidePanel>

  <ConfirmDialog
    v-model:open="deleteOpen"
    :title="t('categories.deleteTitle', { name: pendingDelete?.name ?? '' })"
    :message="t('categories.deleteMessage')"
    :confirm-label="t('common.delete')"
    destructive
    @confirm="onDelete(pendingDelete)"
  />
</template>
