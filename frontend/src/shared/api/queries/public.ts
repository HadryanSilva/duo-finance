import { useQuery } from '@tanstack/vue-query'
import { useHttp } from '@/app/providers'
import type { RegistrationMode } from '../types'

/** Whether sign-up is open or needs an invitation/access code. Public and effectively static. */
export function useRegistrationMode(options: { enabled?: () => boolean } = {}) {
  const http = useHttp()
  return useQuery({
    queryKey: ['registration-mode'],
    queryFn: async (): Promise<RegistrationMode> =>
      (await http.get<{ mode: RegistrationMode }>('/public/registration-mode')).mode,
    staleTime: Infinity,
    ...(options.enabled ? { enabled: options.enabled } : {}),
  })
}
