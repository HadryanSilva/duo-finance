<script setup lang="ts">
import { toTypedSchema } from '@vee-validate/zod'
import { useForm } from 'vee-validate'
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useHttp } from '@/app/providers'
import { safeRedirect } from '@/shared/session/safe-redirect'
import { useSession } from '@/shared/session/session-store'
import { Button } from '@/shared/ui/button'
import PageHeader from '@/shared/ui/PageHeader.vue'
import { login } from '../api/auth-api'
import PasswordField from '../components/PasswordField.vue'
import TextField from '../components/TextField.vue'
import { loginSchema } from '../schemas'

const { t } = useI18n()
const http = useHttp()
const session = useSession()
const route = useRoute()
const router = useRouter()

const { defineField, handleSubmit, errors, isSubmitting } = useForm({
  validationSchema: computed(() => toTypedSchema(loginSchema(t))),
})
const [email, emailAttrs] = defineField('email')
const [password, passwordAttrs] = defineField('password')

const failed = ref(false)

const submit = handleSubmit(async (values) => {
  failed.value = false
  try {
    session.applyAuth(await login(http, values))
  } catch {
    // Whatever went wrong, the message is the same: it must not reveal which part was wrong.
    failed.value = true
    return
  }
  await router.push(safeRedirect(route.query.redirect))
})
</script>

<template>
  <PageHeader :title="t('pages.login')" :subtitle="t('auth.login.subtitle')" />
  <p
    v-if="route.query.reset"
    role="status"
    class="mb-4 rounded-md bg-muted p-3 text-sm text-foreground"
  >
    {{ t('auth.login.resetDone') }}
  </p>
  <form class="grid gap-4" novalidate @submit.prevent="submit">
    <p v-if="failed" role="alert" class="rounded-md bg-muted p-3 text-sm text-destructive">
      {{ t('errors.INVALID_CREDENTIALS') }}
    </p>
    <TextField
      v-model="email"
      v-bind="emailAttrs"
      :label="t('auth.email')"
      :error="errors.email"
      type="email"
      autocomplete="email"
    />
    <PasswordField
      v-model="password"
      v-bind="passwordAttrs"
      :label="t('auth.password')"
      :error="errors.password"
      autocomplete="current-password"
    />
    <RouterLink to="/forgot-password" class="text-sm text-primary hover:underline">
      {{ t('auth.login.forgot') }}
    </RouterLink>
    <Button type="submit" :disabled="isSubmitting">{{ t('common.signIn') }}</Button>
  </form>
  <p class="mt-6 text-center text-sm text-muted-foreground">
    {{ t('auth.login.noAccount') }}
    <RouterLink to="/register" class="text-primary hover:underline">
      {{ t('auth.login.createAccount') }}
    </RouterLink>
  </p>
</template>
