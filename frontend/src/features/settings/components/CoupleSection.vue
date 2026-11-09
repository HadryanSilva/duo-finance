<script setup lang="ts">
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import { toast } from 'vue-sonner'
import { useAccount, useCancelInvitation, useLeaveAccount } from '@/shared/api/queries/account'
import { errorMessage } from '@/shared/i18n/error-codes'
import { clearOnboarded } from '@/shared/session/onboarding'
import { useSession } from '@/shared/session/session-store'
import { Button } from '@/shared/ui/button'
import ConfirmDialog from '@/shared/ui/ConfirmDialog.vue'
import CoupleNameForm from './CoupleNameForm.vue'
import InvitePartnerForm from './InvitePartnerForm.vue'
import SettingsSection from './SettingsSection.vue'

defineProps<{ emailVerified: boolean }>()

const { t, locale } = useI18n()
const router = useRouter()
const session = useSession()
const account = useAccount()
const cancelInvitation = useCancelInvitation()
const leaveMutation = useLeaveAccount()

const members = computed(() => account.data.value?.members ?? [])
const pending = computed(() => account.data.value?.pendingInvitation ?? null)
const canInvite = computed(() => members.value.length === 1 && !pending.value)
const canLeave = computed(() => members.value.length === 2)

const expiry = (instant: string) =>
  new Intl.DateTimeFormat(locale.value, { dateStyle: 'medium' }).format(new Date(instant))

const actionError = ref<string | null>(null)
const leaveOpen = ref(false)

async function cancel(id: string) {
  actionError.value = null
  try {
    await cancelInvitation.mutateAsync(id)
    toast.success(t('settings.couple.inviteCancelled'))
  } catch (e) {
    actionError.value = errorMessage(e)
  }
}

async function leave() {
  actionError.value = null
  try {
    const response = await leaveMutation.mutateAsync()
    // Clears the query cache, so nothing of the old Account is left to render.
    session.switchAccount(response)
    // The new solo account is onboarded like a fresh one.
    clearOnboarded(response.user.id)
  } catch (e) {
    actionError.value = errorMessage(e)
    return
  }
  await router.push('/onboarding')
}
</script>

<template>
  <SettingsSection :title="t('settings.couple.title')">
    <p v-if="account.isError.value" role="alert" class="text-sm text-destructive">
      {{ errorMessage(account.error.value) }}
    </p>
    <template v-else-if="account.data.value">
      <p v-if="actionError" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
        {{ actionError }}
      </p>

      <CoupleNameForm :initial-name="account.data.value.name" />

      <div class="grid gap-2">
        <h3 class="text-sm font-medium text-foreground">{{ t('settings.couple.members') }}</h3>
        <ul class="grid gap-2">
          <li
            v-for="m in members"
            :key="m.userId"
            class="flex flex-wrap items-baseline gap-x-2 text-sm"
          >
            <span class="font-medium text-foreground">{{ m.displayName }}</span>
            <span v-if="m.userId === session.user?.id" class="text-muted-foreground">
              ({{ t('settings.couple.you') }})
            </span>
            <span class="text-muted-foreground">{{ m.email }}</span>
          </li>
        </ul>
      </div>

      <div v-if="pending" class="grid gap-2">
        <h3 class="text-sm font-medium text-foreground">{{ t('settings.couple.pending') }}</h3>
        <div class="flex flex-wrap items-center justify-between gap-2 text-sm">
          <span class="text-foreground">
            {{
              t('settings.couple.pendingFor', {
                email: pending.email,
                date: expiry(pending.expiresAt),
              })
            }}
          </span>
          <Button
            type="button"
            size="sm"
            variant="outline"
            :disabled="cancelInvitation.isPending.value"
            @click="cancel(pending.id)"
          >
            {{ t('settings.couple.cancelInvite') }}
          </Button>
        </div>
      </div>

      <InvitePartnerForm v-if="canInvite" :email-verified="emailVerified" />

      <div v-if="canLeave">
        <Button type="button" variant="outline" @click="leaveOpen = true">
          {{ t('settings.couple.leave') }}
        </Button>
        <ConfirmDialog
          v-model:open="leaveOpen"
          destructive
          :title="t('settings.couple.leaveTitle')"
          :message="t('settings.couple.leaveMessage')"
          :confirm-label="t('settings.couple.leaveConfirm')"
          @confirm="leave"
        />
      </div>
    </template>
  </SettingsSection>
</template>
