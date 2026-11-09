import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useHttp } from '@/app/providers'
import type {
  AccountResponse,
  AuthResponse,
  CreateInvitationRequest,
  InvitationSummary,
  UpdateAccountRequest,
} from '../types'

const ACCOUNT_KEY = ['account'] as const

export function useAccount() {
  const http = useHttp()
  return useQuery({
    queryKey: ACCOUNT_KEY,
    queryFn: () => http.get<AccountResponse>('/account'),
  })
}

export function useRenameAccount() {
  const http = useHttp()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (name: string) =>
      http.patch<AccountResponse, UpdateAccountRequest>('/account', { name }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ACCOUNT_KEY }),
  })
}

export function useCreateInvitation() {
  const http = useHttp()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (email: string) =>
      http.post<InvitationSummary, CreateInvitationRequest>('/account/invitations', { email }),
    onSuccess: () => qc.invalidateQueries({ queryKey: ACCOUNT_KEY }),
  })
}

export function useCancelInvitation() {
  const http = useHttp()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) => http.del(`/account/invitations/${encodeURIComponent(id)}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: ACCOUNT_KEY }),
  })
}

/** Leaving the couple returns the tokens of the user's new solo Account. */
export function useLeaveAccount() {
  const http = useHttp()
  return useMutation({ mutationFn: () => http.post<AuthResponse>('/account/leave') })
}
