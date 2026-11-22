import { useMutation, useQuery, useQueryClient } from '@tanstack/vue-query'
import { computed, toValue, type MaybeRefOrGetter } from 'vue'
import { useHttp } from '@/app/providers'
import type {
  CommitResponse,
  PageResponseImportBatchResponse,
  PreviewResponse,
} from '@/shared/api/types'

const KEY = ['imports'] as const
export const IMPORT_BATCHES_PAGE_SIZE = 20

/**
 * One reviewed row of the commit. The OpenAPI schema types the `decisions` multipart part as a
 * plain string, so the element shape is declared here (mirrors the backend's RowDecision).
 */
export interface RowDecision {
  rowId: string
  include: boolean
  categoryId: string | null
}

/** product name -> bank account id; null leaves the product unmapped (its rows are skipped). */
export type ProductMapping = Record<string, string | null>

export interface PreviewInput {
  file: File
  bankAccountId: string
  productMapping?: ProductMapping
}

export interface CommitInput extends PreviewInput {
  decisions: RowDecision[]
}

const jsonPart = (value: unknown) => new Blob([JSON.stringify(value)], { type: 'application/json' })

function toForm({ file, bankAccountId, productMapping }: PreviewInput): FormData {
  const form = new FormData()
  form.append('file', file)
  form.append('bankAccountId', bankAccountId)
  if (productMapping) form.append('productMapping', jsonPart(productMapping))
  return form
}

/** An import moves balances, the dashboard and budget spending, like any transaction. */
function useInvalidate() {
  const qc = useQueryClient()
  return () =>
    Promise.all(
      [['transactions'], ['dashboard'], ['month-review'], ['bank-accounts'], ['budgets'], KEY].map(
        (queryKey) => qc.invalidateQueries({ queryKey }),
      ),
    )
}

export function usePreviewImport() {
  const http = useHttp()
  return useMutation({
    mutationFn: (input: PreviewInput) =>
      http.postForm<PreviewResponse>('/imports/revolut/preview', toForm(input)),
  })
}

export function useCommitImport() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (input: CommitInput) => {
      const form = toForm(input)
      form.append('decisions', jsonPart(input.decisions))
      return http.postForm<CommitResponse>('/imports/revolut/commit', form)
    },
    onSuccess: invalidate,
  })
}

export function useImportBatches(page: MaybeRefOrGetter<number>) {
  const http = useHttp()
  return useQuery({
    queryKey: computed(() => [...KEY, 'batches', toValue(page)]),
    queryFn: () =>
      http.get<PageResponseImportBatchResponse>('/imports', {
        page: toValue(page),
        size: IMPORT_BATCHES_PAGE_SIZE,
      }),
  })
}

export function useUndoImport() {
  const http = useHttp()
  const invalidate = useInvalidate()
  return useMutation({
    mutationFn: (batchId: string) =>
      http.post<void>(`/imports/${encodeURIComponent(batchId)}/undo`),
    onSuccess: invalidate,
  })
}
