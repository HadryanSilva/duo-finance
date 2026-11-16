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

/** Every goal change moves balances, suggestions and the dashboard's free balance. */
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
    onSuccess: invalidate,
  })
}

export function useCreateGoal() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (body: CreateGoalRequest) => http.post<GoalResponse>('/goals', body),
    onSuccess: invalidate,
  })
}

export function useUpdateGoal() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateGoalRequest }) =>
      http.put<GoalResponse>(`/goals/${enc(id)}`, body),
    onSuccess: invalidate,
  })
}

export function useDeleteGoal() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => http.del(`/goals/${enc(id)}`),
    onSuccess: invalidate,
  })
}

export function useCompleteGoal() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => http.post<GoalResponse>(`/goals/${enc(id)}/complete`),
    onSuccess: invalidate,
  })
}

export function useReopenGoal() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (id: string) => http.post<GoalResponse>(`/goals/${enc(id)}/reopen`),
    onSuccess: invalidate,
  })
}

export function useContribute() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: ContributeRequest }) =>
      http.post<GoalMovementResponse, ContributeRequest>(`/goals/${enc(id)}/contributions`, body),
    onSuccess: invalidate,
  })
}

export function useSkipMonth() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: SkipRequest }) =>
      http.post<GoalMovementResponse, SkipRequest>(`/goals/${enc(id)}/skips`, body),
    onSuccess: invalidate,
  })
}

export function useDeposit() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: GoalAmountRequest }) =>
      http.post<GoalMovementResponse, GoalAmountRequest>(`/goals/${enc(id)}/deposits`, body),
    onSuccess: invalidate,
  })
}

export function useWithdraw() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: GoalAmountRequest }) =>
      http.post<GoalMovementResponse, GoalAmountRequest>(`/goals/${enc(id)}/withdrawals`, body),
    onSuccess: invalidate,
  })
}

export function useUndoMovement() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: ({ goalId, movementId }: { goalId: string; movementId: string }) =>
      http.del(`/goals/${enc(goalId)}/movements/${enc(movementId)}`),
    onSuccess: invalidate,
  })
}
