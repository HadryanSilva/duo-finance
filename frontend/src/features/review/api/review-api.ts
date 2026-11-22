import { useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import { useHttp } from '@/app/providers'
import type { MonthReviewResponse } from '@/shared/api/types'

/**
 * Review of a closed `YYYY-MM` month; without one, the server picks the last closed month. That answer is also
 * cached under its own month, so asking for it next does not fetch it again.
 */
export function useMonthReview(month: MaybeRefOrGetter<string | undefined>) {
  const http = useHttp()
  const queryClient = useQueryClient()
  return useQuery({
    queryKey: computed(() => ['month-review', toValue(month) ?? 'latest']),
    queryFn: async () => {
      const wanted = toValue(month)
      const data = await http.get<MonthReviewResponse>(
        '/month-review',
        wanted ? { month: wanted } : undefined,
      )
      if (!wanted) queryClient.setQueryData(['month-review', data.month], data)
      return data
    },
  })
}
