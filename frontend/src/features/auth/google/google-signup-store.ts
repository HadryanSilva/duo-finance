import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface GoogleSignup {
  ticket: string
  email: string
  displayName: string
  invitationToken?: string
  /** Kept so the login can be retried if completing the sign-up finds the e-mail already linked. */
  credential: string
}

/** Pending Google sign-up between the button and the completion page. Memory only, never persisted. */
export const useGoogleSignup = defineStore('google-signup', () => {
  const signup = ref<GoogleSignup | null>(null)
  const set = (value: GoogleSignup) => {
    signup.value = value
  }
  const clear = () => {
    signup.value = null
  }
  return { signup, set, clear }
})
