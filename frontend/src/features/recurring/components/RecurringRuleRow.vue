<script setup lang="ts">
import { ArrowLeftRight, Pause, Pencil, Play, Trash } from '@lucide/vue'
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import type { RecurringRuleResponse } from '@/shared/api/types'
import { formatBusinessDate } from '@/shared/lib/dates'
import { swatchInkClass } from '@/shared/lib/contrast'
import type { Locale } from '@/shared/lib/money'
import ArchivedBadge from '@/shared/ui/ArchivedBadge.vue'
import AuthorAvatar from '@/shared/ui/AuthorAvatar.vue'
import { Badge } from '@/shared/ui/badge'
import { Button } from '@/shared/ui/button'
import CategoryIcon from '@/shared/ui/CategoryIcon.vue'
import MoneyText from '@/shared/ui/MoneyText.vue'

const props = defineProps<{ rule: RecurringRuleResponse }>()
const emit = defineEmits<{ edit: []; toggle: []; remove: [] }>()

const { t, locale } = useI18n()

const category = computed(() => props.rule.category)
const origin = computed(() => props.rule.bankAccount)
const destination = computed(() => props.rule.destinationBankAccount)
const isTransfer = computed(() => props.rule.kind === 'TRANSFER')

/** The description, or the category name (or "Transfer") when it is empty. */
const title = computed(
  () =>
    props.rule.description ||
    category.value?.name ||
    (isTransfer.value ? t('transactions.kind.TRANSFER') : ''),
)
const nextDate = computed(() => formatBusinessDate(props.rule.nextRunDate, locale.value as Locale))
</script>

<template>
  <li class="flex items-center gap-3 rounded-md px-3 py-3">
    <span
      v-if="category"
      class="flex size-9 shrink-0 items-center justify-center rounded-full"
      :class="swatchInkClass(category.color)"
      :style="{ backgroundColor: category.color }"
    >
      <!-- the color comes from the API as data, not from the design tokens -->
      <CategoryIcon :name="category.icon" />
    </span>
    <span
      v-else
      class="flex size-9 shrink-0 items-center justify-center rounded-full bg-muted text-muted-foreground"
    >
      <ArrowLeftRight class="size-4" aria-hidden="true" />
    </span>

    <span class="flex min-w-0 flex-1 flex-col gap-1">
      <span class="flex min-w-0 flex-wrap items-center gap-2">
        <span class="truncate font-medium text-foreground">{{ title }}</span>
        <ArchivedBadge v-if="category?.archived || origin?.archived || destination?.archived" />
        <Badge v-if="rule.finished" variant="outline">{{ t('recurring.finished') }}</Badge>
      </span>
      <span class="flex min-w-0 flex-wrap items-center gap-x-2 text-sm text-muted-foreground">
        <span>{{ t(`recurring.frequency.${rule.frequency}`) }}</span>
        <span aria-hidden="true">·</span>
        <span>{{ t('recurring.next', { date: nextDate }) }}</span>
        <template v-if="isTransfer">
          <span aria-hidden="true">·</span>
          <span>{{
            t('transactions.transferRoute', {
              from: origin?.name ?? '',
              to: destination?.name ?? '',
            })
          }}</span>
        </template>
        <template v-else-if="origin">
          <span aria-hidden="true">·</span>
          <span>{{ origin.name }}</span>
        </template>
      </span>
    </span>

    <AuthorAvatar :author="rule.createdBy" />
    <MoneyText
      :cents="rule.amountCents"
      :kind="rule.kind"
      class="shrink-0 text-right font-medium"
    />
    <div class="flex shrink-0 items-center">
      <Button
        variant="ghost"
        size="icon"
        :aria-label="t('recurring.edit', { name: title })"
        @click="emit('edit')"
      >
        <Pencil class="size-4" aria-hidden="true" />
      </Button>
      <Button
        v-if="!rule.finished"
        variant="ghost"
        size="icon"
        :aria-label="
          t(rule.active ? 'recurring.deactivate' : 'recurring.activate', { name: title })
        "
        @click="emit('toggle')"
      >
        <Pause v-if="rule.active" class="size-4" aria-hidden="true" />
        <Play v-else class="size-4" aria-hidden="true" />
      </Button>
      <Button
        variant="ghost"
        size="icon"
        :aria-label="t('recurring.remove', { name: title })"
        @click="emit('remove')"
      >
        <Trash class="size-4" aria-hidden="true" />
      </Button>
    </div>
  </li>
</template>
