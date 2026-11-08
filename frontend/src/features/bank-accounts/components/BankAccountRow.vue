<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import type { BankAccountResponse } from '@/shared/api/types'
import ArchivedBadge from '@/shared/ui/ArchivedBadge.vue'
import MoneyText from '@/shared/ui/MoneyText.vue'
import RowActions from '@/shared/ui/RowActions.vue'

const props = defineProps<{ account: BankAccountResponse; holderName: string | null }>()
const emit = defineEmits<{ edit: []; archive: []; unarchive: []; remove: [] }>()

const { t } = useI18n()
const label = (action: 'edit' | 'archive' | 'unarchive' | 'delete') =>
  t(`bankAccounts.actions.${action}`, { name: props.account.name })
</script>

<template>
  <li class="flex items-center gap-3 rounded-lg border border-border bg-card p-4">
    <div class="min-w-0 flex-1">
      <div class="flex flex-wrap items-center gap-2">
        <span class="truncate font-medium text-foreground">{{ account.name }}</span>
        <ArchivedBadge v-if="account.archived" />
      </div>
      <p class="text-sm text-muted-foreground">
        <span>{{ t(`bankAccountType.${account.type}`) }}</span>
        <template v-if="holderName">
          <span aria-hidden="true"> · </span>
          <span>{{ t('bankAccounts.heldBy', { name: holderName }) }}</span>
        </template>
      </p>
    </div>
    <MoneyText :cents="account.balanceCents" class="font-semibold text-foreground" />
    <RowActions
      :archived="account.archived"
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
