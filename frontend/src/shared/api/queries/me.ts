import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useHttp } from '@/app/providers'
import type { AuthResponse, MeResponse } from '../types'

/** Sends the signed-in user a new email verification link. */
export function useResendVerification() {
  const http = useHttp()
  return useMutation({ mutationFn: () => http.post<void>('/auth/resend-verification') })
}

const ME_KEY = ['me'] as const

export function useMe() {
  const http = useHttp()
  return useQuery({ queryKey: ME_KEY, queryFn: () => http.get<MeResponse>('/me') })
}

export function useUpdateMe() {
  const http = useHttp()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: { displayName?: string; locale?: string }) =>
      http.patch<MeResponse>('/me', body),
    onSuccess: (me) => qc.setQueryData(ME_KEY, me),
  })
}

/** Every old session is revoked: the response carries the new tokens. */
export function useChangePassword() {
  const http = useHttp()
  return useMutation({
    mutationFn: (body: { currentPassword: string; newPassword: string }) =>
      http.put<AuthResponse>('/me/password', body),
  })
}

export function useDeleteMe() {
  const http = useHttp()
  return useMutation({ mutationFn: (password: string) => http.del('/me', { password }) })
}
