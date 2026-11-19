<script setup lang="ts">
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { Button } from '@/shared/ui/button'
import { startGoogleSignIn } from './google-redirect'

const props = defineProps<{ clientId: string; returnTo: string; invitationToken?: string }>()

// Served from public/: bound, not static, so the compiler leaves the path alone.
const GOOGLE_LOGO = '/google-g.svg'

const { t } = useI18n()
const route = useRoute()

// Our own button: nothing from Google loads until the user clicks, then the whole page goes to Google's sign-in.
function signIn() {
  startGoogleSignIn({
    clientId: props.clientId,
    returnTo: props.returnTo,
    cancelTo: route.fullPath,
    ...(props.invitationToken ? { invitationToken: props.invitationToken } : {}),
  })
}
</script>

<template>
  <Button type="button" variant="outline" class="w-full gap-3" @click="signIn">
    <img :src="GOOGLE_LOGO" alt="" class="size-5" />
    {{ t('auth.continueWithGoogle') }}
  </Button>
</template>
