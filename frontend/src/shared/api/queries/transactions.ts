import { useInfiniteQuery, useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import { useHttp } from '@/app/providers'
import type {
  PageResponseTransactionResponse,
  TransactionKind,
  TransactionRequest,
  TransactionResponse,
} from '../types'

const KEY = ['transactions'] as const
export const TRANSACTIONS_PAGE_SIZE = 50

/** Filters of GET /transactions; mirrored in the transactions page's query string. */
export type TransactionFilter = {
  from?: string
  to?: string
  kind?: TransactionKind
  categoryId?: string
  bankAccountId?: string
  createdBy?: string
  q?: string
}

/** Pages of 50, newest date first; `hasNextPage` while `page < totalPages - 1`. */
export function useTransactions(filter: MaybeRefOrGetter<TransactionFilter>) {
  const http = useHttp()
  return useInfiniteQuery({
    queryKey: computed(() => [...KEY, toValue(filter)]),
    queryFn: ({ pageParam }) =>
      http.get<PageResponseTransactionResponse>('/transactions', {
        ...toValue(filter),
        page: pageParam,
        size: TRANSACTIONS_PAGE_SIZE,
      }),
    initialPageParam: 0,
    getNextPageParam: (last) => (last.page < last.totalPages - 1 ? last.page + 1 : undefined),
  })
}

export function useTransaction(id: MaybeRefOrGetter<string | null>) {
  const http = useHttp()
  return useQuery({
    queryKey: computed(() => [...KEY, 'detail', toValue(id)]),
    queryFn: () =>
      http.get<TransactionResponse>(`/transactions/${encodeURIComponent(toValue(id) ?? '')}`),
    enabled: () => toValue(id) !== null,
  })
}

/** A transaction moves balances, the dashboard and budget spending. */
function useInvalidate() {
  const qc = useQueryClient()
  return () =>
    Promise.all(
      [KEY, ['dashboard'], ['bank-accounts'], ['budgets']].map((queryKey) =>
        qc.invalidateQueries({ queryKey }),
      ),
    )
}

export function useCreateTransaction() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (body: TransactionRequest) => http.post<TransactionResponse>('/transactions', body),
    onSuccess: invalidate,
  })
}

export function useUpdateTransaction() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: TransactionRequest }) =>
      http.put<TransactionResponse>(`/transactions/${encodeURIComponent(id)}`, body),
    onSuccess: invalidate,
  })
}

export function useDeleteTransaction() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => http.del(`/transactions/${encodeURIComponent(id)}`),
    onSuccess: invalidate,
  })
}
