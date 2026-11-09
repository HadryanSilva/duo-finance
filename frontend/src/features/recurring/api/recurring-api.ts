import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { useHttp } from '@/app/providers'
import type {
  CreateRecurringRuleRequest,
  RecurringRuleResponse,
  UpdateRecurringRuleRequest,
} from '@/shared/api/types'

const KEY = ['recurring-rules'] as const

export function useRecurringRules() {
  const http = useHttp()
  return useQuery({
    queryKey: KEY,
    queryFn: () => http.get<RecurringRuleResponse[]>('/recurring-rules'),
  })
}

/** A rule with a past start generates transactions right away, which move balances and budgets. */
function useInvalidate() {
  const qc = useQueryClient()
  return () =>
    Promise.all(
      [KEY, ['transactions'], ['dashboard'], ['bank-accounts'], ['budgets']].map((queryKey) =>
        qc.invalidateQueries({ queryKey }),
      ),
    )
}

const path = (id: string, action = '') => `/recurring-rules/${encodeURIComponent(id)}${action}`

export function useCreateRecurringRule() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (body: CreateRecurringRuleRequest) =>
      http.post<RecurringRuleResponse>('/recurring-rules', body),
    onSuccess: invalidate,
  })
}

export function useUpdateRecurringRule() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateRecurringRuleRequest }) =>
      http.put<RecurringRuleResponse>(path(id), body),
    onSuccess: invalidate,
  })
}

export function useSetRecurringRuleActive() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, active }: { id: string; active: boolean }) =>
      http.post<RecurringRuleResponse>(path(id, active ? '/activate' : '/deactivate')),
    onSuccess: invalidate,
  })
}

export function useDeleteRecurringRule() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => http.del(path(id)),
    onSuccess: invalidate,
  })
}
