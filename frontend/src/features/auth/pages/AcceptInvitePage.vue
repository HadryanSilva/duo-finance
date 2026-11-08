<script setup lang="ts">
import { useQuery } from '@tanstack/vue-query'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useHttp } from '@/app/providers'
import { isApiError } from '@/shared/api/problem'
import { errorMessage } from '@/shared/i18n/error-codes'
import { useSession } from '@/shared/session/session-store'
import { Button } from '@/shared/ui/button'
import ConfirmDialog from '@/shared/ui/ConfirmDialog.vue'
import PageHeader from '@/shared/ui/PageHeader.vue'
import { acceptInvitation, invitationPreview } from '../api/auth-api'

const { t } = useI18n()
const http = useHttp()
const session = useSession()
const route = useRoute()
const router = useRouter()

const token = computed(() => String(route.params.token))
const accepted = ref(false)

const preview = useQuery({
  queryKey: ['invitation-preview', token],
  queryFn: () => invitationPreview(http, token.value),
  // switchAccount clears the cache: don't refetch a consumed invitation while navigating away.
  enabled: computed(() => !accepted.value),
  retry: false,
})

const authenticated = computed(() => session.status === 'authenticated')
const confirmOpen = ref(false)
const busy = ref(false)
const error = ref<string | null>(null)

const loginTo = computed(() => ({ path: '/login', query: { redirect: `/invite/${token.value}` } }))
const registerTo = computed(() => ({
  path: '/register',
  query: { invite: token.value, email: preview.data.value?.email },
}))

async function accept(confirmDiscard: boolean) {
  busy.value = true
  error.value = null
  try {
    const auth = await acceptInvitation(http, token.value, confirmDiscard)
    accepted.value = true
    session.switchAccount(auth)
  } catch (e) {
    if (!confirmDiscard && isApiError(e) && e.status === 409 && e.code === 'ACCOUNT_HAS_DATA') {
      confirmOpen.value = true
    } else if (isApiError(e) && e.code === 'NOT_FOUND') {
      // The API answers 404 for an invitation addressed to another email.
      error.value = t('invite.notForThisAccount')
    } else {
      error.value = errorMessage(e)
    }
    return
  } finally {
    busy.value = false
  }
  await router.push('/dashboard')
}
</script>

<template>
  <PageHeader :title="t('invite.title')" :subtitle="t('invite.subtitle')" />
  <p v-if="preview.isPending.value" class="text-sm text-muted-foreground" aria-busy="true">
    {{ t('invite.loading') }}
  </p>
  <p
    v-else-if="preview.isError.value"
    role="alert"
    class="rounded-md bg-muted p-3 text-sm text-destructive"
  >
    {{ errorMessage(preview.error.value) }}
  </p>
  <div v-else-if="preview.data.value" class="grid gap-6">
    <dl class="grid gap-3 text-sm">
      <div>
        <dt class="text-muted-foreground">{{ t('invite.invitedBy') }}</dt>
        <dd class="font-medium text-foreground">
          {{ preview.data.value.inviterName ?? t('invite.unknownInviter') }}
        </dd>
      </div>
      <div>
        <dt class="text-muted-foreground">{{ t('invite.account') }}</dt>
        <dd class="font-medium text-foreground">{{ preview.data.value.accountName }}</dd>
      </div>
      <div>
        <dt class="text-muted-foreground">{{ t('invite.email') }}</dt>
        <dd class="font-medium text-foreground">{{ preview.data.value.email }}</dd>
      </div>
    </dl>
    <p v-if="error" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ error }}
    </p>
    <Button v-if="authenticated" :disabled="busy" @click="accept(false)">
      {{ t('invite.accept') }}
    </Button>
    <Button v-else-if="preview.data.value.existingUser" as-child>
      <RouterLink :to="loginTo">{{ t('invite.signIn') }}</RouterLink>
    </Button>
    <Button v-else as-child>
      <RouterLink :to="registerTo">{{ t('invite.createAccount') }}</RouterLink>
    </Button>
  </div>
  <ConfirmDialog
    v-model:open="confirmOpen"
    :title="t('invite.confirmTitle')"
    :message="t('invite.confirmMessage')"
    :confirm-label="t('invite.confirmLabel')"
    destructive
    @confirm="accept(true)"
  />
</template>
