import { useMediaQuery } from '@vueuse/core'
import type { Ref } from 'vue'

/** Tailwind's `md` breakpoint (768px): sidebar, side sheets and inline row actions from here up. */
export const DESKTOP_QUERY = '(min-width: 48rem)'

export function useIsDesktop(): Ref<boolean> {
  return useMediaQuery(DESKTOP_QUERY)
}
