import { useQuery } from '@tanstack/vue-query'
import { computed } from 'vue'
import { useHttp } from '@/app/providers'
import type { RegistrationModeResponse } from '../types'

/** Registration mode and the Google client id (null when Google sign-in is off). Public and effectively static. */
export function useAuthConfig(options: { enabled?: () => boolean } = {}) {
  const http = useHttp()
  return useQuery({
    queryKey: ['registration-mode'],
    queryFn: () => http.get<RegistrationModeResponse>('/public/registration-mode'),
    staleTime: Infinity,
    ...(options.enabled ? { enabled: options.enabled } : {}),
  })
}

/** Whether sign-up is open or needs an invitation/access code. */
export function useRegistrationMode(options: { enabled?: () => boolean } = {}) {
  const config = useAuthConfig(options)
  const data = computed(() => config.data.value?.mode)
  return { ...config, data }
}
