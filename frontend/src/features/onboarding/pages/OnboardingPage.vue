<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { useAccount } from '@/shared/api/queries/account'
import { useBankAccounts } from '@/shared/api/queries/bank-accounts'
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
const bankAccounts = useBankAccounts()

/**
 * The onboarded mark lives in this browser only. A partner on a new device whose account is
 * already a couple, or already has a bank account, skips the wizard. Decided once, on the first
 * load: the wizard's own bank account must not cut the last step short.
 */
const deciding = ref(true)
let decided = false
watch(
  [account.data, bankAccounts.data, bankAccounts.isError],
  ([acc, banks, banksFailed]) => {
    // A failed bank-account list does not block the wizard; the account decides alone then.
    if (decided || !acc || (!banks && !banksFailed)) return
    decided = true
    if (acc.members.length >= 2 || (banks?.length ?? 0) > 0) {
      if (session.user) markOnboarded(session.user.id)
      void router.replace('/dashboard')
      return
    }
    deciding.value = false
  },
  { immediate: true },
)

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
    <p
      v-if="account.isError.value"
      role="alert"
      class="rounded-md bg-muted p-3 text-sm text-destructive"
    >
      {{ errorMessage(account.error.value) }}
    </p>
    <p v-else-if="deciding" class="text-sm text-muted-foreground" aria-busy="true">…</p>
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
