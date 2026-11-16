<script setup lang="ts">
import { computed, nextTick, ref, useTemplateRef } from 'vue'
import { useI18n } from 'vue-i18n'
import { RouterLink } from 'vue-router'
import { useContribute, useSkipMonth } from '@/shared/api/queries/goals'
import type { DashboardResponse, GoalResponse, GoalSuggestion } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { currentMonth, formatMonth } from '@/shared/lib/dates'
import type { Locale } from '@/shared/lib/money'
import { Badge } from '@/shared/ui/badge'
import { Button } from '@/shared/ui/button'
import { Card, CardContent, CardHeader, CardTitle } from '@/shared/ui/card'
import MoneyInput from '@/shared/ui/MoneyInput.vue'
import MoneyText from '@/shared/ui/MoneyText.vue'
import { Skeleton } from '@/shared/ui/skeleton'
import CardEmpty from './CardEmpty.vue'

/** `undefined` while the dashboard loads. */
const props = defineProps<{ data?: DashboardResponse }>()

const { t, locale } = useI18n()
const contribute = useContribute()
const skip = useSkipMonth()

const goals = computed(() => props.data?.goals)
const monthLabel = computed(() =>
  goals.value ? formatMonth(goals.value.month, locale.value as Locale) : '',
)

/** Goals with a monthly share need a salary to suggest from; an archived category is not usable. */
const needsSalaryCategory = computed(() => {
  const g = goals.value
  if (!g) return false
  const category = g.salaryCategory
  return g.goals.some((x) => x.monthlyPercent > 0) && (category === null || category.archived)
})

/**
 * A usable salary category that received nothing in a month that already began: the goals with a
 * monthly share get no suggestion, so the card says why.
 */
const emptySalaryCategory = computed(() => {
  const g = goals.value
  if (!g || g.salaryCents !== 0 || g.month > currentMonth()) return null
  const category = g.salaryCategory
  if (category === null || category.archived) return null
  return g.goals.some((x) => x.monthlyPercent > 0) ? category.name : null
})

function percent(g: GoalResponse): number {
  if (g.targetCents <= 0) return 0
  return Math.min(100, Math.max(0, Math.floor((g.savedCents * 100) / g.targetCents)))
}

// One suggestion at a time: the goal being adjusted, the typed amount and the last failure.
const adjusting = ref<string | null>(null)
const amount = ref<number | null>(null)
const failure = ref<{ goalId: string; goalName: string; error: unknown } | null>(null)
const busyGoal = ref<string | null>(null)

// The adjust form replaces the row's buttons, so focus is moved by hand when it opens and closes.
const card = useTemplateRef<{ $el: HTMLElement }>('card')
async function focusIn(selector: string) {
  await nextTick()
  card.value?.$el.querySelector<HTMLElement>(selector)?.focus()
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

const isSuggested = (goalId: string) =>
  goals.value?.suggestions.some((x) => x.goalId === goalId) ?? false

/**
 * Runs a Confirmar / Pular / Ajustar save. The refetch that follows usually removes the row, so
 * the neighbours are read before it: focus then goes to the next suggestion, else the previous
 * one, else the title. A failure on a row that is still there keeps focus on `returnTo`.
 */
async function run(s: GoalSuggestion, returnTo: string, action: () => Promise<unknown>) {
  const list = goals.value?.suggestions ?? []
  const i = list.findIndex((x) => x.goalId === s.goalId)
  const neighbours = [list[i + 1], list[i - 1]].flatMap((x) => (x ? [x.goalId] : []))
  failure.value = null
  busyGoal.value = s.goalId
  let saved = false
  try {
    await action()
    adjusting.value = null
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
  goals.value?.goals.find((g) => g.id === s.goalId)?.remainingCents ?? 0

function saveAdjusted(s: GoalSuggestion) {
  const amountCents = amount.value
  amountError.value = null
  if (amountCents === null || amountCents <= 0) {
    amountError.value = 'positive'
    return
  }
  const remaining = remainingOf(s)
  if (amountCents > remaining) {
    amountError.value = 'tooHigh'
    return
  }
  void run(s, `[data-goal-amount="${s.goalId}"]`, () =>
    contribute.mutateAsync({ id: s.goalId, body: { month: s.month, amountCents } }),
  )
}
</script>

<template>
  <Card ref="card">
    <CardHeader class="flex-row items-center justify-between gap-2">
      <CardTitle data-goals-title tabindex="-1" class="outline-none">
        {{ t('dashboard.goals.title') }}
      </CardTitle>
      <Button v-if="goals?.goals.length" as-child variant="link" size="sm">
        <RouterLink to="/goals" :aria-label="t('dashboard.goals.viewAllLabel')">
          {{ t('dashboard.goals.viewAll') }}
        </RouterLink>
      </Button>
    </CardHeader>
    <CardContent class="grid gap-5">
      <div v-if="!goals" class="grid gap-4">
        <Skeleton class="h-10 w-full" />
        <Skeleton class="h-10 w-full" />
      </div>
      <CardEmpty
        v-else-if="!goals.goals.length"
        :title="t('dashboard.goals.emptyTitle')"
        :description="t('dashboard.goals.emptyDescription')"
        :cta="t('dashboard.goals.emptyCta')"
        to="/goals?new=1"
      />
      <template v-else>
        <section v-if="goals.suggestions.length" class="grid gap-3">
          <h4 class="text-sm font-medium text-foreground">
            <i18n-t keypath="dashboard.goals.suggestionsHeader" tag="span">
              <template #month>{{ monthLabel }}</template>
              <template #salary><MoneyText :cents="goals.salaryCents" /></template>
            </i18n-t>
          </h4>
          <ul class="grid gap-3" :aria-label="t('dashboard.goals.suggestionsLabel')">
            <li
              v-for="s in goals.suggestions"
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
              <form
                v-if="adjusting === s.goalId"
                class="grid gap-2"
                @submit.prevent="saveAdjusted(s)"
              >
                <div class="flex flex-wrap items-end gap-2">
                  <MoneyInput
                    v-model="amount"
                    :data-goal-amount="s.goalId"
                    :label="t('dashboard.goals.amountFor', { name: s.goalName })"
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
                  keypath="dashboard.goals.amountTooHigh"
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
                  :aria-label="t('dashboard.goals.confirmLabel', { name: s.goalName })"
                  :data-goal-confirm="s.goalId"
                  @click="confirm(s)"
                >
                  {{ t('dashboard.goals.confirm') }}
                </Button>
                <Button
                  size="sm"
                  variant="outline"
                  :disabled="busyGoal === s.goalId"
                  :aria-label="t('dashboard.goals.adjustLabel', { name: s.goalName })"
                  :data-goal-adjust="s.goalId"
                  @click="startAdjust(s)"
                >
                  {{ t('dashboard.goals.adjust') }}
                </Button>
                <Button
                  size="sm"
                  variant="ghost"
                  :disabled="busyGoal === s.goalId"
                  :aria-label="t('dashboard.goals.skipLabel', { name: s.goalName })"
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
        </section>
        <!-- The refetch after a failure can remove the row (the partner decided first): say it here. -->
        <p
          v-if="failure && !isSuggested(failure.goalId)"
          role="alert"
          class="text-sm text-destructive"
        >
          {{
            t('dashboard.goals.failureFor', {
              name: failure.goalName,
              message: errorMessage(failure.error),
            })
          }}
        </p>

        <RouterLink
          v-if="needsSalaryCategory"
          to="/goals"
          class="text-sm text-brand underline-offset-4 hover:underline"
        >
          {{ t('dashboard.goals.salaryHint') }}
        </RouterLink>
        <p v-else-if="emptySalaryCategory" class="text-sm text-muted-foreground">
          {{ t('dashboard.goals.noSalary', { month: monthLabel, name: emptySalaryCategory }) }}
        </p>

        <ul class="grid gap-4" :aria-label="t('dashboard.goals.listLabel')">
          <li v-for="g in goals.goals" :key="g.id" class="grid gap-1">
            <div class="flex flex-wrap items-baseline justify-between gap-x-3 text-sm">
              <span class="flex min-w-0 items-center gap-2">
                <span class="truncate font-medium text-foreground">{{ g.name }}</span>
                <Badge v-if="g.status === 'REACHED'" variant="outline">
                  {{ t('goals.status.REACHED') }}
                </Badge>
              </span>
              <span class="flex items-baseline gap-x-3">
                <span class="text-muted-foreground">
                  <MoneyText :cents="g.savedCents" class="font-medium text-foreground" />
                  /
                  <MoneyText :cents="g.targetCents" />
                </span>
                <span class="font-medium tabular-nums text-foreground">{{ percent(g) }}%</span>
              </span>
            </div>
            <div
              role="progressbar"
              :aria-label="g.name"
              :aria-valuenow="percent(g)"
              aria-valuemin="0"
              aria-valuemax="100"
              :aria-valuetext="t('goals.progress', { percent: percent(g) })"
              class="h-2 w-full overflow-hidden rounded-full bg-muted"
            >
              <div class="h-full rounded-full bg-brand" :style="{ width: `${percent(g)}%` }" />
            </div>
          </li>
        </ul>
      </template>
    </CardContent>
  </Card>
</template>
