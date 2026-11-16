import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import { useHttp } from '@/app/providers'
import type {
  ContributeRequest,
  CreateGoalRequest,
  GoalAmountRequest,
  GoalMovementResponse,
  GoalResponse,
  GoalSettingsResponse,
  GoalsOverview,
  SkipRequest,
  UpdateGoalRequest,
  UpdateGoalSettingsRequest,
} from '../types'

const KEY = ['goals'] as const
const enc = encodeURIComponent

/** Goals of a `YYYY-MM` month with suggestions and projections. */
export function useGoals(month: MaybeRefOrGetter<string>) {
  const http = useHttp()
  return useQuery({
    queryKey: computed(() => [...KEY, toValue(month)]),
    queryFn: () => http.get<GoalsOverview>('/goals', { month: toValue(month) }),
  })
}

export function useGoalMovements(id: MaybeRefOrGetter<string | null>) {
  const http = useHttp()
  return useQuery({
    queryKey: computed(() => [...KEY, 'movements', toValue(id)]),
    queryFn: () => http.get<GoalMovementResponse[]>(`/goals/${enc(toValue(id) ?? '')}/movements`),
    enabled: () => toValue(id) !== null,
  })
}

export function useGoalSettings() {
  const http = useHttp()
  return useQuery({
    queryKey: [...KEY, 'settings'],
    queryFn: () => http.get<GoalSettingsResponse>('/goals/settings'),
  })
}

/**
 * Every goal change moves balances, suggestions and the dashboard's free balance. Refreshed on
 * failure too: a conflict usually means the partner acted first, so what is on screen is stale.
 */
function useInvalidate() {
  const qc = useQueryClient()
  return () =>
    Promise.all([KEY, ['dashboard']].map((queryKey) => qc.invalidateQueries({ queryKey })))
}

export function useUpdateGoalSettings() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (body: UpdateGoalSettingsRequest) =>
      http.put<GoalSettingsResponse>('/goals/settings', body),
    onSettled: invalidate,
  })
}

export function useCreateGoal() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (body: CreateGoalRequest) => http.post<GoalResponse>('/goals', body),
    onSettled: invalidate,
  })
}

export function useUpdateGoal() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateGoalRequest }) =>
      http.put<GoalResponse>(`/goals/${enc(id)}`, body),
    onSettled: invalidate,
  })
}

export function useDeleteGoal() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => http.del(`/goals/${enc(id)}`),
    onSettled: invalidate,
  })
}

export function useCompleteGoal() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => http.post<GoalResponse>(`/goals/${enc(id)}/complete`),
    onSettled: invalidate,
  })
}

export function useReopenGoal() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => http.post<GoalResponse>(`/goals/${enc(id)}/reopen`),
    onSettled: invalidate,
  })
}

export function useContribute() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: ContributeRequest }) =>
      http.post<GoalResponse, ContributeRequest>(`/goals/${enc(id)}/contributions`, body),
    onSettled: invalidate,
  })
}

export function useSkipMonth() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: SkipRequest }) =>
      http.post<GoalResponse, SkipRequest>(`/goals/${enc(id)}/skips`, body),
    onSettled: invalidate,
  })
}

export function useDeposit() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: GoalAmountRequest }) =>
      http.post<GoalResponse, GoalAmountRequest>(`/goals/${enc(id)}/deposits`, body),
    onSettled: invalidate,
  })
}

export function useWithdraw() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: GoalAmountRequest }) =>
      http.post<GoalResponse, GoalAmountRequest>(`/goals/${enc(id)}/withdrawals`, body),
    onSettled: invalidate,
  })
}

export function useUndoMovement() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ goalId, movementId }: { goalId: string; movementId: string }) =>
      http.del(`/goals/${enc(goalId)}/movements/${enc(movementId)}`),
    onSettled: invalidate,
  })
}
