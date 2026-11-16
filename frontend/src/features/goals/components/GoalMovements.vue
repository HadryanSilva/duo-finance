<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useGoalMovements, useUndoMovement } from '@/shared/api/queries/goals'
import type { GoalMovementResponse } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { formatInstant, formatMonth } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import AuthorAvatar from '@/shared/ui/AuthorAvatar.vue'
import { Button } from '@/shared/ui/button'
import ConfirmDialog from '@/shared/ui/ConfirmDialog.vue'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import { moneyLabel } from '../lib/goal-copy'

const props = defineProps<{ goalId: string; canUndo: boolean }>()

const { t, locale } = useI18n()
const movements = useGoalMovements(() => props.goalId)
const undo = useUndoMovement()

const pending = ref<GoalMovementResponse | null>(null)
const confirmOpen = computed({
  get: () => pending.value !== null,
  set: (open: boolean) => {
    if (!open) pending.value = null
  },
})
const error = ref<string | null>(null)

async function onUndo() {
  const target = pending.value
  if (!target) return
  error.value = null
  try {
    await undo.mutateAsync({ goalId: props.goalId, movementId: target.id })
  } catch (e) {
    error.value = errorMessage(e)
  }
}

/** Names a movement for assistive tech and the confirmation: type, amount and when. */
function undoParams(m: GoalMovementResponse) {
  return {
    type: t(`goals.history.type.${m.type}`).toLocaleLowerCase(locale.value),
    amount: moneyLabel(m.amountCents, locale.value as Locale),
    when: when(m),
  }
}

function when(m: GoalMovementResponse): string {
  return m.month
    ? formatMonth(m.month, locale.value as Locale)
    : formatInstant(m.createdAt, locale.value as Locale)
}
</script>

<template>
  <div class="grid gap-3">
    <p v-if="error" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ error }}
    </p>
    <Skeleton v-if="movements.isPending.value" class="h-16 w-full" />
    <p v-else-if="movements.isError.value" role="alert" class="text-sm text-destructive">
      {{ errorMessage(movements.error.value) }}
    </p>
    <p v-else-if="!movements.data.value?.length" class="text-sm text-muted-foreground">
      {{ t('goals.history.empty') }}
    </p>
    <ul v-else class="grid gap-2">
      <li
        v-for="m in movements.data.value"
        :key="m.id"
        class="flex items-center gap-3 rounded-md border border-border p-3"
      >
        <AuthorAvatar :author="m.createdBy" />
        <span class="flex min-w-0 flex-1 flex-col text-sm">
          <span class="font-medium text-foreground">{{ t(`goals.history.type.${m.type}`) }}</span>
          <span class="text-muted-foreground">{{ when(m) }}</span>
          <span v-if="m.note" class="truncate text-muted-foreground">{{ m.note }}</span>
        </span>
        <MoneyText
          :cents="m.amountCents"
          :kind="m.type === 'WITHDRAWAL' ? 'EXPENSE' : 'NEUTRAL'"
          class="shrink-0 font-medium"
        />
        <Button
          v-if="canUndo"
          variant="ghost"
          size="sm"
          :aria-label="t('goals.history.undoLabel', undoParams(m))"
          @click="pending = m"
        >
          {{ t('goals.history.undo') }}
        </Button>
      </li>
    </ul>
  </div>

  <ConfirmDialog
    v-model:open="confirmOpen"
    :title="t('goals.history.undoTitle')"
    :message="pending ? t('goals.history.undoMessage', undoParams(pending)) : ''"
    :confirm-label="t('goals.history.undo')"
    destructive
    @confirm="onUndo"
  />
</template>
