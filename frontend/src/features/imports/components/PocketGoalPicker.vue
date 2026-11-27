<script setup lang="ts">
import { nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import type { GoalResponse } from '@/shared/api/types'
import { Dialog, DialogContent, DialogHeader, DialogTitle } from '@/shared/ui/dialog'
import GoalForm from '@/shared/ui/GoalForm.vue'
import NativeSelect from '@/shared/ui/NativeSelect.vue'

/**
 * The goal of one pocket move: an active goal, none, or a new goal created in a dialog over the
 * review (pre-filled with the pocket's name) and then selected.
 */
const props = defineProps<{
  goalId: string | null
  /** The pocket's name, null when the statement's wording did not give it. */
  pocket: string | null
  description: string
  /** Active goals. */
  goals: GoalResponse[]
  disabled?: boolean
  invalid?: boolean
  describedBy?: string
}>()
const emit = defineEmits<{ select: [goalId: string | null] }>()

const { t } = useI18n()
const CREATE = '__create__'

// Mirrors the select: picking "create" must not stay shown, so it is put back on the current goal.
const choice = ref(props.goalId ?? '')
watch(
  () => props.goalId,
  (id) => (choice.value = id ?? ''),
)
const creating = ref(false)

function onChoice(value: string | undefined) {
  if (value === CREATE) {
    // follow the select to "create" first, so putting it back is a change the select re-renders
    choice.value = CREATE
    void nextTick(() => (choice.value = props.goalId ?? ''))
    creating.value = true
    return
  }
  choice.value = value ?? ''
  emit('select', value || null)
}

function onCreated(goal: GoalResponse) {
  creating.value = false
  emit('select', goal.id)
}
</script>

<template>
  <NativeSelect
    class="sm:w-56"
    :model-value="choice"
    :disabled="disabled"
    :aria-label="t('imports.pocket.goal', { description })"
    :aria-invalid="invalid ? 'true' : undefined"
    :aria-describedby="describedBy"
    @update:model-value="onChoice"
  >
    <option value="">{{ t('imports.pocket.none') }}</option>
    <option v-for="g in goals" :key="g.id" :value="g.id">{{ g.name }}</option>
    <option :value="CREATE">
      {{
        pocket ? t('imports.pocket.create', { name: pocket }) : t('imports.pocket.createUnnamed')
      }}
    </option>
  </NativeSelect>

  <Dialog v-model:open="creating">
    <DialogContent>
      <DialogHeader>
        <DialogTitle>{{ t('goals.createTitle') }}</DialogTitle>
      </DialogHeader>
      <GoalForm
        v-if="creating"
        :initial="{ name: pocket ?? '', revolutPocket: pocket }"
        @done="onCreated"
      />
    </DialogContent>
  </Dialog>
</template>
