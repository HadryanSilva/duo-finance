<script setup lang="ts">
import { computed, nextTick, ref, useTemplateRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { useContribute, useSkipMonth } from '@/shared/api/queries/goals'
import type { GoalSuggestion, GoalsOverview } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { currentMonth, formatMonth } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { Button } from '@/shared/ui/button'
import MoneyInput from '@/shared/ui/MoneyInput.vue'
import MoneyText from '@/shared/ui/MoneyText.vue'

/** The month's goals overview (`GET /goals`): its suggestions are confirmed, adjusted or skipped here. */
const props = defineProps<{ overview: GoalsOverview }>()

const { t, locale } = useI18n()
const contribute = useContribute()
const skip = useSkipMonth()

const monthLabel = computed(() => formatMonth(props.overview.month, locale.value as Locale))
const suggestions = computed(() => props.overview.suggestions)

/**
 * A usable salary category that received nothing in a month that already began: the goals with a
 * monthly share get no suggestion, so the section says why.
 */
const emptySalaryCategory = computed(() => {
  const g = props.overview
  if (g.salaryCents !== 0 || g.month > currentMonth()) return null
  const category = g.salaryCategory
  if (category === null || category.archived) return null
  return g.goals.some((x) => x.monthlyPercent > 0) ? category.name : null
})

// One suggestion at a time: the goal being adjusted, the typed amount and the last failure.
const adjusting = ref<string | null>(null)
const amount = ref<number | null>(null)
const failure = ref<{ goalId: string; goalName: string; error: unknown } | null>(null)
const busyGoal = ref<string | null>(null)
/** Set once a suggestion is decided on this visit: the section then stays to say all is decided. */
const decidedHere = ref(false)

const shown = computed(
  () =>
    suggestions.value.length > 0 ||
    decidedHere.value ||
    failure.value !== null ||
    emptySalaryCategory.value !== null,
)

// The adjust form replaces the row's buttons, so focus is moved by hand when it opens and closes.
const root = useTemplateRef<HTMLElement>('root')
async function focusIn(selector: string) {
  await nextTick()
  root.value?.querySelector<HTMLElement>(selector)?.focus()
}

function startAdjust(s: GoalSuggestion) {
  adjusting.value = s.goalId
  amountError.value = null
  failure.value = null
  amount.value = s.suggestedCents
  void focusIn(`[data-goal-amount="${s.goalId}"]`)
}

function cancelAdjust(s: GoalSuggestion) {
  adjusting.value = null
  amountError.value = null
  failure.value = null
  void focusIn(`[data-goal-adjust="${s.goalId}"]`)
}

const isSuggested = (goalId: string) => suggestions.value.some((x) => x.goalId === goalId)

/**
 * Runs a Confirmar / Pular / Ajustar save. The refetch that follows usually removes the row, so
 * the neighbours are read before it: focus then goes to the next suggestion, else the previous
 * one, else the heading. A failure on a row that is still there keeps focus on `returnTo`.
 */
async function run(s: GoalSuggestion, returnTo: string, action: () => Promise<unknown>) {
  const list = suggestions.value
  const i = list.findIndex((x) => x.goalId === s.goalId)
  const neighbours = [list[i + 1], list[i - 1]].flatMap((x) => (x ? [x.goalId] : []))
  failure.value = null
  busyGoal.value = s.goalId
  let saved = false
  try {
    await action()
    adjusting.value = null
    decidedHere.value = true
    saved = true
  } catch (error) {
    failure.value = { goalId: s.goalId, goalName: s.goalName, error }
  } finally {
    busyGoal.value = null
  }
  await nextTick()
  if (!saved && isSuggested(s.goalId)) {
    void focusIn(returnTo)
    return
  }
  const next = neighbours.find(isSuggested)
  void focusIn(next ? `[data-goal-confirm="${next}"]` : '[data-goals-title]')
}

// The amount shown is sent: the server would otherwise recompute it from the salary at click time.
const confirm = (s: GoalSuggestion) =>
  run(s, `[data-goal-confirm="${s.goalId}"]`, () =>
    contribute.mutateAsync({
      id: s.goalId,
      body: { month: s.month, amountCents: s.suggestedCents },
    }),
  )
const skipMonth = (s: GoalSuggestion) =>
  run(s, `[data-goal-skip="${s.goalId}"]`, () =>
    skip.mutateAsync({ id: s.goalId, body: { month: s.month } }),
  )

const amountError = ref<'positive' | 'tooHigh' | null>(null)
const remainingOf = (s: GoalSuggestion) =>
  props.overview.goals.find((g) => g.id === s.goalId)?.remainingCents ?? 0

function saveAdjusted(s: GoalSuggestion) {
  const amountCents = amount.value
  amountError.value = null
  if (amountCents === null || amountCents <= 0) {
    amountError.value = 'positive'
    return
  }
  if (amountCents > remainingOf(s)) {
    amountError.value = 'tooHigh'
    return
  }
  void run(s, `[data-goal-amount="${s.goalId}"]`, () =>
    contribute.mutateAsync({ id: s.goalId, body: { month: s.month, amountCents } }),
  )
}
</script>

<template>
  <section
    v-if="shown"
    ref="root"
    aria-labelledby="goal-suggestions-title"
    class="grid gap-3 rounded-lg border border-border bg-card p-4"
  >
    <h2
      id="goal-suggestions-title"
      data-goals-title
      tabindex="-1"
      class="text-lg font-semibold text-foreground outline-none"
    >
      <i18n-t v-if="suggestions.length" keypath="goals.suggestions.header" tag="span">
        <template #month>{{ monthLabel }}</template>
        <template #salary><MoneyText :cents="overview.salaryCents" /></template>
      </i18n-t>
      <template v-else-if="decidedHere">
        {{ t('goals.suggestions.allDone', { month: monthLabel }) }}
      </template>
      <template v-else>{{ t('goals.suggestions.title', { month: monthLabel }) }}</template>
    </h2>

    <ul v-if="suggestions.length" class="grid gap-3" :aria-label="t('goals.suggestions.listLabel')">
      <li
        v-for="s in suggestions"
        :key="s.goalId"
        class="grid gap-2 rounded-lg border border-border p-3"
      >
        <div class="flex flex-wrap items-baseline justify-between gap-x-3 text-sm">
          <span class="min-w-0 truncate font-medium text-foreground">{{ s.goalName }}</span>
          <span class="text-muted-foreground">
            {{ s.monthlyPercent }}% →
            <MoneyText :cents="s.suggestedCents" class="font-medium text-foreground" />
          </span>
        </div>
        <form v-if="adjusting === s.goalId" class="grid gap-2" @submit.prevent="saveAdjusted(s)">
          <div class="flex flex-wrap items-end gap-2">
            <MoneyInput
              v-model="amount"
              :data-goal-amount="s.goalId"
              :label="t('goals.suggestions.amountFor', { name: s.goalName })"
            />
            <Button type="submit" size="sm" :disabled="busyGoal === s.goalId">
              {{ t('common.save') }}
            </Button>
            <Button
              type="button"
              size="sm"
              variant="ghost"
              :disabled="busyGoal === s.goalId"
              @click="cancelAdjust(s)"
            >
              {{ t('common.cancel') }}
            </Button>
          </div>
          <i18n-t keypath="goals.remaining" tag="p" class="text-sm text-muted-foreground">
            <template #amount><MoneyText :cents="remainingOf(s)" /></template>
          </i18n-t>
          <p v-if="amountError === 'positive'" role="alert" class="text-sm text-destructive">
            {{ t('goals.amount.amountPositive') }}
          </p>
          <i18n-t
            v-else-if="amountError === 'tooHigh'"
            keypath="goals.suggestions.amountTooHigh"
            tag="p"
            role="alert"
            class="text-sm text-destructive"
          >
            <template #amount><MoneyText :cents="remainingOf(s)" /></template>
          </i18n-t>
        </form>
        <div v-else class="flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            :disabled="busyGoal === s.goalId"
            :aria-label="t('goals.suggestions.confirmLabel', { name: s.goalName })"
            :data-goal-confirm="s.goalId"
            @click="confirm(s)"
          >
            {{ t('goals.suggestions.confirm') }}
          </Button>
          <Button
            size="sm"
            variant="outline"
            :disabled="busyGoal === s.goalId"
            :aria-label="t('goals.suggestions.adjustLabel', { name: s.goalName })"
            :data-goal-adjust="s.goalId"
            @click="startAdjust(s)"
          >
            {{ t('goals.suggestions.adjust') }}
          </Button>
          <Button
            size="sm"
            variant="ghost"
            :disabled="busyGoal === s.goalId"
            :aria-label="t('goals.suggestions.skipLabel', { name: s.goalName })"
            :data-goal-skip="s.goalId"
            @click="skipMonth(s)"
          >
            {{ t('common.skip') }}
          </Button>
        </div>
        <p v-if="failure?.goalId === s.goalId" role="alert" class="text-sm text-destructive">
          {{ errorMessage(failure.error) }}
        </p>
      </li>
    </ul>

    <!-- The refetch after a failure can remove the row (the partner decided first): say it here. -->
    <p v-if="failure && !isSuggested(failure.goalId)" role="alert" class="text-sm text-destructive">
      {{
        t('goals.suggestions.failureFor', {
          name: failure.goalName,
          message: errorMessage(failure.error),
        })
      }}
    </p>

    <p v-if="emptySalaryCategory" class="text-sm text-muted-foreground">
      {{ t('goals.suggestions.noSalary', { month: monthLabel, name: emptySalaryCategory }) }}
    </p>
  </section>
</template>
