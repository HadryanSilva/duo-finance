import { onScopeDispose, readonly, ref, type Ref } from 'vue'
import { todayIso } from './dates'

const CHECK_EVERY_MS = 60_000

/**
 * The browser's local date (YYYY-MM-DD), kept current: it is checked every minute and whenever the tab becomes
 * visible again, so a page left open overnight moves to the new day. Stops with the calling scope.
 */
export function useToday(): Readonly<Ref<string>> {
  const today = ref(todayIso())
  const refresh = () => {
    const now = todayIso()
    if (now !== today.value) today.value = now
  }
  const timer = setInterval(refresh, CHECK_EVERY_MS)
  document.addEventListener('visibilitychange', refresh)
  onScopeDispose(() => {
    clearInterval(timer)
    document.removeEventListener('visibilitychange', refresh)
  })
  return readonly(today)
}
