import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import { useHttp } from '@/app/providers'
import type {
  CategoryKind,
  CategoryRequest,
  CategoryResponse,
  UpdateCategoryRequest,
} from '../types'

const KEY = ['categories'] as const

export function useCategories(
  kind?: MaybeRefOrGetter<CategoryKind | undefined>,
  includeArchived: MaybeRefOrGetter<boolean> = false,
) {
  const http = useHttp()
  return useQuery({
    queryKey: computed(() => [
      ...KEY,
      { kind: toValue(kind), includeArchived: toValue(includeArchived) },
    ]),
    queryFn: () => {
      const params: Record<string, string> = {}
      const k = toValue(kind)
      if (k) params.kind = k
      if (toValue(includeArchived)) params.includeArchived = 'true'
      return http.get<CategoryResponse[]>('/categories', params)
    },
  })
}

/** Invalidates categories and the dashboard; `withTransactions` too when rows show category names. */
function useInvalidate(withTransactions: boolean) {
  const qc = useQueryClient()
  return () =>
    Promise.all([
      qc.invalidateQueries({ queryKey: KEY }),
      qc.invalidateQueries({ queryKey: ['dashboard'] }),
      ...(withTransactions ? [qc.invalidateQueries({ queryKey: ['transactions'] })] : []),
    ])
}

export function useCreateCategory() {
  const http = useHttp()
  const invalidate = useInvalidate(false)
  return useMutation({
    mutationFn: (body: CategoryRequest) => http.post<CategoryResponse>('/categories', body),
    onSuccess: invalidate,
  })
}

export function useUpdateCategory() {
  const http = useHttp()
  const invalidate = useInvalidate(true)
  return useMutation({
    mutationFn: ({ id, body }: { id: string; body: UpdateCategoryRequest }) =>
      http.put<CategoryResponse>(`/categories/${encodeURIComponent(id)}`, body),
    onSuccess: invalidate,
  })
}

export function useArchiveCategory() {
  const http = useHttp()
  const invalidate = useInvalidate(true)
  return useMutation({
    mutationFn: (id: string) =>
      http.post<CategoryResponse>(`/categories/${encodeURIComponent(id)}/archive`),
    onSuccess: invalidate,
  })
}

export function useUnarchiveCategory() {
  const http = useHttp()
  const invalidate = useInvalidate(true)
  return useMutation({
    mutationFn: (id: string) =>
      http.post<CategoryResponse>(`/categories/${encodeURIComponent(id)}/unarchive`),
    onSuccess: invalidate,
  })
}

export function useDeleteCategory() {
  const http = useHttp()
  const invalidate = useInvalidate(false)
  return useMutation({
    mutationFn: (id: string) => http.del(`/categories/${encodeURIComponent(id)}`),
    onSuccess: invalidate,
  })
}
