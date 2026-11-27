<script setup lang="ts">
import { CircleCheck } from '@lucide/vue'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useSkipMonth } from '@/shared/api/queries/goals'
import type { GoalMonthTarget, GoalsOverview } from '@/shared/api/types'
import { errorMessage } from '@/shared/i18n/error-codes'
import { currentMonth, formatMonth } from '@/shared/lib/dates'
import { lastOpened } from '@/shared/lib/last-opened'
import type { Locale } from '@/shared/lib/money'
import { Button } from '@/shared/ui/button'
import MoneyText from '@/shared/ui/MoneyText.vue'
import SidePanel from '@/shared/ui/SidePanel.vue'
import { goalPercent } from '../lib/goal-copy'
import GoalAmountForm from './GoalAmountForm.vue'

// Two roots (the section and its panel): the page's spacing classes go on the section.
defineOptions({ inheritAttrs: false })

/** The month's goals overview (`GET /goals`): each goal's target is met by contributions, or skipped. */
const props = defineProps<{ overview: GoalsOverview }>()

const { t, locale } = useI18n()
const skip = useSkipMonth()

const monthLabel = computed(() => formatMonth(props.overview.month, locale.value as Locale))
const targets = computed(() => props.overview.monthTargets)

/**
 * A usable salary category that received nothing in a month that already began: the goals with a
 * monthly share get no target, so the section says why.
 */
const emptySalaryCategory = computed(() => {
  const g = props.overview
  if (g.salaryCents !== 0 || g.month > currentMonth()) return null
  const category = g.salaryCategory
  if (category === null || category.archived) return null
  return g.goals.some((x) => x.monthlyPercent > 0) ? category.name : null
})

const shown = computed(() => targets.value.length > 0 || emptySalaryCategory.value !== null)

const isMet = (x: GoalMonthTarget) => x.targetCents > 0 && x.contributedCents >= x.targetCents
// With no target (no salary yet) any contribution decides the month: Pular hides, Registrar stays.
const percentOf = (x: GoalMonthTarget) => goalPercent(x.contributedCents, x.targetCents)

// The contribute form opens in a panel, pre-filled with what is left of the target.
const registering = ref<GoalMonthTarget | null>(null)
const registeringShown = lastOpened(registering)
const registerOpen = computed({
  get: () => registering.value !== null,
  set: (open: boolean) => {
    if (!open) registering.value = null
  },
})
const registerGoal = computed(() => {
  const id = registeringShown.value?.goalId
  return props.overview.goals.find((g) => g.id === id) ?? null
})
const remainingOf = (x: GoalMonthTarget) => Math.max(0, x.targetCents - x.contributedCents)

const failure = ref<{ goalId: string; error: unknown } | null>(null)
const busyGoal = ref<string | null>(null)

async function skipMonth(x: GoalMonthTarget) {
  failure.value = null
  busyGoal.value = x.goalId
  try {
    await skip.mutateAsync({ id: x.goalId, body: { month: x.month } })
  } catch (error) {
    failure.value = { goalId: x.goalId, error }
  } finally {
    busyGoal.value = null
  }
}
</script>

<template>
  <section
    v-if="shown"
    v-bind="$attrs"
    aria-labelledby="goal-month-targets-title"
    class="grid gap-3 rounded-lg border border-border bg-card p-4"
  >
    <h2 id="goal-month-targets-title" class="text-lg font-semibold text-foreground">
      <i18n-t keypath="goals.monthTargets.header" tag="span">
        <template #month>{{ monthLabel }}</template>
        <template #salary><MoneyText :cents="overview.salaryCents" /></template>
      </i18n-t>
    </h2>

    <ul v-if="targets.length" class="grid gap-3" :aria-label="t('goals.monthTargets.listLabel')">
      <li
        v-for="x in targets"
        :key="x.goalId"
        class="grid gap-2 rounded-lg border border-border p-3"
      >
        <div class="flex flex-wrap items-baseline justify-between gap-x-3 text-sm">
          <span class="min-w-0 truncate font-medium text-foreground">{{ x.goalName }}</span>
          <span class="text-muted-foreground">
            <MoneyText :cents="x.contributedCents" class="font-medium text-foreground" />
            <template v-if="x.targetCents > 0">
              {{ t('goals.monthTargets.of') }}
              <MoneyText :cents="x.targetCents" />
            </template>
          </span>
        </div>
        <p v-if="x.targetCents === 0" class="text-sm text-muted-foreground">
          {{
            overview.salaryCents === 0
              ? t('goals.monthTargets.noSalaryTarget')
              : t('goals.monthTargets.noTarget')
          }}
        </p>
        <div
          v-else
          role="progressbar"
          :aria-label="x.goalName"
          :aria-valuenow="percentOf(x)"
          aria-valuemin="0"
          aria-valuemax="100"
          :aria-valuetext="t('goals.monthTargets.progress', { percent: percentOf(x) })"
          class="h-2 w-full overflow-hidden rounded-full bg-muted"
        >
          <div class="h-full rounded-full bg-brand" :style="{ width: `${percentOf(x)}%` }" />
        </div>
        <p v-if="x.skipped" class="text-sm text-muted-foreground">
          {{ t('goals.monthTargets.skipped') }}
        </p>
        <p v-else-if="isMet(x)" class="flex items-center gap-2 text-sm font-medium text-income">
          <CircleCheck class="size-4" aria-hidden="true" />
          {{ t('goals.monthTargets.met') }}
        </p>
        <div v-else class="flex flex-wrap items-center gap-2">
          <Button
            size="sm"
            :disabled="busyGoal === x.goalId"
            :aria-label="t('goals.monthTargets.registerLabel', { name: x.goalName })"
            @click="registering = x"
          >
            {{ t('goals.monthTargets.register') }}
          </Button>
          <Button
            v-if="x.contributedCents === 0"
            size="sm"
            variant="ghost"
            :disabled="busyGoal === x.goalId"
            :aria-label="t('goals.monthTargets.skipLabel', { name: x.goalName })"
            @click="skipMonth(x)"
          >
            {{ t('goals.monthTargets.skip') }}
          </Button>
        </div>
        <p v-if="failure?.goalId === x.goalId" role="alert" class="text-sm text-destructive">
          {{ errorMessage(failure.error) }}
        </p>
      </li>
    </ul>

    <p v-if="emptySalaryCategory" class="text-sm text-muted-foreground">
      {{ t('goals.monthTargets.noSalary', { month: monthLabel, name: emptySalaryCategory }) }}
    </p>
  </section>

  <SidePanel v-model:open="registerOpen" :title="t('goals.actions.contribute')">
    <GoalAmountForm
      v-if="registering && registerGoal"
      :key="registering.goalId"
      :goal="registerGoal"
      mode="contribute"
      :initial-amount-cents="remainingOf(registering) || undefined"
      @done="registerOpen = false"
    />
  </SidePanel>
</template>
