import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import { useHttp } from '@/app/providers'
import type { BankAccountRequest, BankAccountResponse } from '../types'

const KEY = ['bank-accounts'] as const

export function useBankAccounts(includeArchived: MaybeRefOrGetter<boolean> = false) {
  const http = useHttp()
  return useQuery({
    queryKey: [...KEY, computed(() => toValue(includeArchived))],
    queryFn: () =>
      http.get<BankAccountResponse[]>(
        '/bank-accounts',
        toValue(includeArchived) ? { includeArchived: 'true' } : undefined,
      ),
  })
}

export function useCreateBankAccount() {
  const http = useHttp()
  const qc = useQueryClient()
  return useMutation({
    mutationFn: (body: BankAccountRequest) =>
      http.post<BankAccountResponse>('/bank-accounts', body),
    onSuccess: () =>
      Promise.all([
        qc.invalidateQueries({ queryKey: KEY }),
        qc.invalidateQueries({ queryKey: ['dashboard'] }),
      ]),
  })
}
