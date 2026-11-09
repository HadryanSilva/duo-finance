import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import { useHttp } from '@/app/providers'
import type { BudgetResponse, CreateBudgetRequest, UpdateBudgetRequest } from '@/shared/api/types'

const KEY = ['budgets'] as const

/** Budgets of a `YYYY-MM` month with that month's spending. */
export function useBudgets(month: MaybeRefOrGetter<string>) {
  const http = useHttp()
  return useQuery({
    queryKey: computed(() => [...KEY, toValue(month)]),
    queryFn: () => http.get<BudgetResponse[]>('/budgets', { month: toValue(month) }),
  })
}

function useInvalidate() {
  const qc = useQueryClient()
  return () =>
    Promise.all([KEY, ['dashboard']].map((queryKey) => qc.invalidateQueries({ queryKey })))
}

export function useCreateBudget() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (body: CreateBudgetRequest) => http.post<BudgetResponse>('/budgets', body),
    onSuccess: invalidate,
  })
}

export function useUpdateBudget() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateBudgetRequest }) =>
      http.put<BudgetResponse>(`/budgets/${encodeURIComponent(id)}`, body),
    onSuccess: invalidate,
  })
}

export function useDeleteBudget() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => http.del(`/budgets/${encodeURIComponent(id)}`),
    onSuccess: invalidate,
  })
}
