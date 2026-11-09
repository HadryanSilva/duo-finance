import { useQuery } from '@tanstack/vue-query'
import { useHttp } from '@/app/providers'
import type { SubscriptionResponse } from '../types'

export function useSubscription() {
  const http = useHttp()
  return useQuery({
    queryKey: ['subscription'],
    queryFn: () => http.get<SubscriptionResponse>('/account/subscription'),
  })
}
