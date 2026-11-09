import { QueryClient } from '@tanstack/vue-query'
import { isApiError } from '@/shared/api/problem'

/** Queries retry once, except on client errors (4xx), which a retry would not fix. */
export function shouldRetryQuery(failureCount: number, error: unknown): boolean {
  if (isApiError(error) && error.status >= 400 && error.status < 500) return false
  return failureCount < 1
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: { staleTime: 30_000, retry: shouldRetryQuery },
      mutations: { retry: false },
    },
  })
}

export const queryClient = createQueryClient()
