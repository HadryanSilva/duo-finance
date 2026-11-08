import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import { useHttp } from '@/app/providers'
import type { BankAccountRequest, BankAccountResponse } from '../types'

const KEY = ['bank-accounts'] as const

export function useBankAccounts(includeArchived: MaybeRefOrGetter<boolean> = false) {
  const http = useHttp()
  return useQuery({
    queryKey: computed(() => [...KEY, { includeArchived: toValue(includeArchived) }]),
    queryFn: () =>
      http.get<BankAccountResponse[]>(
        '/bank-accounts',
        toValue(includeArchived) ? { includeArchived: 'true' } : undefined,
      ),
  })
}

/** Invalidates bank accounts and the dashboard; `withTransactions` too when rows show account names. */
function useInvalidate(withTransactions: boolean) {
  const qc = useQueryClient()
  return () =>
    Promise.all([
      qc.invalidateQueries({ queryKey: KEY }),
      qc.invalidateQueries({ queryKey: ['dashboard'] }),
      ...(withTransactions ? [qc.invalidateQueries({ queryKey: ['transactions'] })] : []),
    ])
}

export function useCreateBankAccount() {
  const http = useHttp()
  const invalidate = useInvalidate(false)
  return useMutation({
    mutationFn: (body: BankAccountRequest) =>
      http.post<BankAccountResponse>('/bank-accounts', body),
    onSuccess: invalidate,
  })
}

export function useUpdateBankAccount() {
  const http = useHttp()
  const invalidate = useInvalidate(true)
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: BankAccountRequest }) =>
      http.put<BankAccountResponse>(`/bank-accounts/${encodeURIComponent(id)}`, body),
    onSuccess: invalidate,
  })
}

export function useArchiveBankAccount() {
  const http = useHttp()
  const invalidate = useInvalidate(true)
  return useMutation({
    mutationFn: (id: string) =>
      http.post<BankAccountResponse>(`/bank-accounts/${encodeURIComponent(id)}/archive`),
    onSuccess: invalidate,
  })
}

export function useUnarchiveBankAccount() {
  const http = useHttp()
  const invalidate = useInvalidate(true)
  return useMutation({
    mutationFn: (id: string) =>
      http.post<BankAccountResponse>(`/bank-accounts/${encodeURIComponent(id)}/unarchive`),
    onSuccess: invalidate,
  })
}

export function useDeleteBankAccount() {
  const http = useHttp()
  const invalidate = useInvalidate(false)
  return useMutation({
    mutationFn: (id: string) => http.del(`/bank-accounts/${encodeURIComponent(id)}`),
    onSuccess: invalidate,
  })
}
