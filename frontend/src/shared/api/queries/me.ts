import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useHttp } from '@/app/providers'
import type {
  AuthResponse,
  ChangePasswordRequest,
  DeleteMeRequest,
  MeResponse,
  UpdateMeRequest,
} from '../types'

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

/** Reads `/me` from the server now (ignoring the cache) and stores it under the `me` query. */
export function useFetchMe() {
  const http = useHttp()
  const qc = useQueryClient()
  return () =>
    qc.fetchQuery({ queryKey: ME_KEY, queryFn: () => http.get<MeResponse>('/me'), staleTime: 0 })
}

export function useUpdateMe() {
  const http = useHttp()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: UpdateMeRequest) => http.patch<MeResponse>('/me', body),
    onSuccess: (me) => qc.setQueryData(ME_KEY, me),
  })
}

/** Every old session is revoked: the response carries the new tokens. */
export function useChangePassword() {
  const http = useHttp()
  return useMutation({
    mutationFn: (body: ChangePasswordRequest) => http.put<AuthResponse>('/me/password', body),
  })
}

export function useDeleteMe() {
  const http = useHttp()
  return useMutation({
    mutationFn: (password: string) => {
      const body: DeleteMeRequest = { password }
      return http.del('/me', body)
    },
  })
}
