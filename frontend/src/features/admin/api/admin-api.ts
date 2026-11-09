import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { toValue, type MaybeRefOrGetter } from 'vue'
import { useHttp } from '@/app/providers'
import type {
  AccessCodeResponse,
  CreatedAccessCodeResponse,
  WaitlistEntryResponse,
} from '@/shared/api/types'
import type { components } from '@/shared/api/schema'

export type WaitlistPage = components['schemas']['WaitlistPageResponse']
export type CreateAccessCodeBody = components['schemas']['CreateAccessCodeRequest']
export type WaitlistStatus = WaitlistEntryResponse['status']

export const WAITLIST_PAGE_SIZE = 20

const WAITLIST_KEY = ['admin', 'waitlist'] as const
const CODES_KEY = ['admin', 'access-codes'] as const

/** `page` is zero-based, like the API. */
export function useWaitlist(
  status: MaybeRefOrGetter<WaitlistStatus>,
  page: MaybeRefOrGetter<number>,
) {
  const http = useHttp()
  return useQuery({
    queryKey: [...WAITLIST_KEY, { status, page }],
    queryFn: () =>
      http.get<WaitlistPage>('/admin/waitlist', {
        status: toValue(status),
        page: toValue(page),
        size: WAITLIST_PAGE_SIZE,
      }),
    // Keep the old rows while paging, but never show another status's rows (with live actions).
    placeholderData: (previous, previousQuery) =>
      (previousQuery?.queryKey[2] as { status?: string } | undefined)?.status === toValue(status)
        ? previous
        : undefined,
  })
}

export function useDecideWaitlistEntry() {
  const http = useHttp()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: ({ id, action }: { id: string; action: 'approve' | 'reject' }) =>
      http.post<WaitlistEntryResponse>(`/admin/waitlist/${encodeURIComponent(id)}/${action}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: WAITLIST_KEY }),
  })
}

export function useAccessCodes() {
  const http = useHttp()
  return useQuery({
    queryKey: CODES_KEY,
    queryFn: () => http.get<AccessCodeResponse[]>('/admin/access-codes'),
  })
}

export function useCreateAccessCode() {
  const http = useHttp()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: CreateAccessCodeBody) =>
      http.post<CreatedAccessCodeResponse>('/admin/access-codes', body),
    // The response carries the plain code: do not keep it in the mutation cache.
    gcTime: 0,
    onSuccess: () => qc.invalidateQueries({ queryKey: CODES_KEY }),
  })
}

export function useRevokeAccessCode() {
  const http = useHttp()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (id: string) =>
      http.post<void>(`/admin/access-codes/${encodeURIComponent(id)}/revoke`),
    onSuccess: () => qc.invalidateQueries({ queryKey: CODES_KEY }),
  })
}
