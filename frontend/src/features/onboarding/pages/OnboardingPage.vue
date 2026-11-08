<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAccount } from '@/shared/api/queries/account'
import { errorMessage } from '@/shared/i18n/error-codes'
import { markOnboarded } from '@/shared/session/onboarding'
import { useSession } from '@/shared/session/session-store'
import { Progress } from '@/shared/ui/progress'
import StepCoupleName from '../components/StepCoupleName.vue'
import StepFirstBankAccount from '../components/StepFirstBankAccount.vue'
import StepInvitePartner from '../components/StepInvitePartner.vue'

const STEPS = ['couple', 'invite', 'bank'] as const

const { t } = useI18n()
const router = useRouter()
const session = useSession()
const account = useAccount()

const step = ref(1)
const progress = computed(() => Math.round((step.value / STEPS.length) * 100))
const current = computed(() => STEPS[step.value - 1] ?? 'couple')

/** Marks onboarding done (also when the last step is skipped) and enters the app. */
async function finish() {
  if (session.user) markOnboarded(session.user.id)
  await router.push('/dashboard')
}

function next() {
  if (step.value < STEPS.length) step.value++
  else void finish()
}
</script>

<template>
  <div class="grid gap-6">
    <div class="grid gap-2">
      <p class="text-sm text-muted-foreground">
        {{ t('onboarding.stepOf', { current: step, total: STEPS.length }) }}
      </p>
      <Progress :model-value="progress" :aria-label="t('onboarding.progress')" />
    </div>
    <header>
      <h1 class="text-2xl font-semibold tracking-tight text-foreground">
        {{ t(`onboarding.${current}.title`) }}
      </h1>
      <p class="mt-1 text-sm text-muted-foreground">{{ t(`onboarding.${current}.subtitle`) }}</p>
    </header>
    <p v-if="account.isPending.value" class="text-sm text-muted-foreground" aria-busy="true">…</p>
    <p
      v-else-if="account.isError.value"
      role="alert"
      class="rounded-md bg-muted p-3 text-sm text-destructive"
    >
      {{ errorMessage(account.error.value) }}
    </p>
    <template v-else>
      <StepCoupleName
        v-if="step === 1"
        :initial-name="account.data.value?.name ?? ''"
        @done="next"
      />
      <StepInvitePartner v-else-if="step === 2" @done="next" />
      <StepFirstBankAccount v-else @done="next" />
    </template>
  </div>
</template>
