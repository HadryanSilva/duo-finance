import { useQuery } from '@tanstack/vue-query'
import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import { useHttp } from '@/app/providers'
import type { DashboardResponse } from '@/shared/api/types'

/** Dashboard of a `YYYY-MM` month. Mutations elsewhere invalidate the `['dashboard']` prefix. */
export function useDashboard(month: MaybeRefOrGetter<string>) {
  const http = useHttp()
  return useQuery({
    queryKey: computed(() => ['dashboard', toValue(month)]),
    queryFn: () => http.get<DashboardResponse>('/dashboard', { month: toValue(month) }),
  })
}
