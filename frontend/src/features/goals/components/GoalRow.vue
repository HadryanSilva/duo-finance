<script setup lang="ts">
import {
  CircleCheck,
  History,
  Pencil,
  PiggyBank,
  RotateCcw,
  Trash,
  Undo2,
  Wallet,
} from '@lucide/vue'
import { computed, type Component } from 'vue'
import { useI18n } from 'vue-i18n'
import type { GoalResponse } from '@/shared/api/types'
import type { Locale } from '@/shared/lib/money'
import { useIsDesktop } from '@/shared/lib/breakpoints'
import ActionsMenu, { type ActionItem } from '@/shared/ui/ActionsMenu.vue'
import { Badge } from '@/shared/ui/badge'
import { Button } from '@/shared/ui/button'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { goalPercent, projectionText } from '../lib/goal-copy'

const props = defineProps<{ goal: GoalResponse }>()
const emit = defineEmits<{
  deposit: []
  withdraw: []
  history: []
  edit: []
  complete: []
  remove: []
  reopen: []
}>()

const { t, locale } = useI18n()

const isCompleted = computed(() => props.goal.status === 'COMPLETED')
const percent = computed(() => goalPercent(props.goal.savedCents, props.goal.targetCents))
const projection = computed(() => projectionText(props.goal, locale.value as Locale))

type ActionKey = 'deposit' | 'withdraw' | 'history' | 'edit' | 'complete' | 'remove' | 'reopen'
interface Action extends ActionItem {
  key: ActionKey
}
const ICONS: Record<ActionKey, Component> = {
  deposit: PiggyBank,
  withdraw: Wallet,
  history: History,
  edit: Pencil,
  complete: CircleCheck,
  remove: Trash,
  reopen: RotateCcw,
}
const ACTIVE_KEYS: ActionKey[] = ['deposit', 'withdraw', 'history', 'edit', 'complete', 'remove']
const COMPLETED_KEYS: ActionKey[] = ['reopen', 'history', 'remove']

const actions = computed<Action[]>(() =>
  (isCompleted.value ? COMPLETED_KEYS : ACTIVE_KEYS).map((key) => ({
    key,
    label: t(`goals.actions.${key}`),
    icon: key === 'reopen' ? Undo2 : ICONS[key],
    destructive: key === 'remove',
  })),
)

// Below md the icon buttons fold into a "⋯" menu.
const isDesktop = useIsDesktop()
function run(key: string) {
  const k = key as ActionKey
  if (k === 'deposit') emit('deposit')
  else if (k === 'withdraw') emit('withdraw')
  else if (k === 'history') emit('history')
  else if (k === 'edit') emit('edit')
  else if (k === 'complete') emit('complete')
  else if (k === 'remove') emit('remove')
  else emit('reopen')
}
</script>

<template>
  <li class="grid gap-3 rounded-lg border border-border bg-card p-4">
    <div class="flex items-center gap-3">
      <span class="flex min-w-0 flex-1 flex-wrap items-center gap-2">
        <span class="truncate font-medium text-foreground">{{ goal.name }}</span>
        <Badge v-if="goal.status !== 'ACTIVE'" variant="outline">
          {{ t(`goals.status.${goal.status}`) }}
        </Badge>
      </span>
      <ActionsMenu
        v-if="!isDesktop"
        class="shrink-0"
        :label="t('common.actionsFor', { name: goal.name })"
        :items="actions"
        @select="run"
      />
      <div v-else class="flex shrink-0 items-center">
        <Button
          v-for="a in actions"
          :key="a.key"
          variant="ghost"
          size="icon"
          :aria-label="t(`goals.actionLabel.${a.key}`, { name: goal.name })"
          :title="a.label"
          @click="run(a.key)"
        >
          <component :is="a.icon" class="size-4" aria-hidden="true" />
        </Button>
      </div>
    </div>

    <div class="grid gap-2">
      <div class="flex flex-wrap items-baseline justify-between gap-x-3 text-sm">
        <span class="text-muted-foreground">
          <MoneyText :cents="goal.savedCents" class="font-medium text-foreground" />
          /
          <MoneyText :cents="goal.targetCents" />
        </span>
        <span class="font-medium tabular-nums text-foreground">{{ percent }}%</span>
      </div>
      <div
        role="progressbar"
        :aria-label="goal.name"
        :aria-valuenow="percent"
        aria-valuemin="0"
        aria-valuemax="100"
        :aria-valuetext="t('goals.progress', { percent })"
        class="h-2 w-full overflow-hidden rounded-full bg-muted"
      >
        <div class="h-full rounded-full bg-brand" :style="{ width: `${percent}%` }" />
      </div>
    </div>

    <div class="flex flex-wrap items-center gap-x-3 gap-y-1 text-sm text-muted-foreground">
      <i18n-t v-if="goal.remainingCents > 0" keypath="goals.remaining" tag="span">
        <template #amount><MoneyText :cents="goal.remainingCents" /></template>
      </i18n-t>
      <span v-if="goal.monthlyPercent > 0 && !isCompleted">
        {{ t('goals.percentOfSalary', { percent: goal.monthlyPercent }) }}
      </span>
    </div>
    <p v-if="projection && !isCompleted" class="text-sm text-muted-foreground">
      {{ projection }}
    </p>
  </li>
</template>
