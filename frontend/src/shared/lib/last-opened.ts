import { shallowRef, watch, type Ref } from 'vue'

/**
 * The last non-null value of a panel or dialog's source. Titles and messages read it: clearing the
 * source on close would change them while the panel or dialog is still animating out.
 */
export function lastOpened<T>(source: Ref<T | null>) {
  const last = shallowRef<T | null>(source.value)
  watch(source, (value) => {
    if (value !== null) last.value = value
  })
  return last
}
