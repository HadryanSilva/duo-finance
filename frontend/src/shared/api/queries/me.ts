import { useMutation } from '@tanstack/vue-query'
import { useHttp } from '@/app/providers'

/** Sends the signed-in user a new email verification link. */
export function useResendVerification() {
  const http = useHttp()
  return useMutation({ mutationFn: () => http.post<void>('/auth/resend-verification') })
}
