import { Blob as NodeBlob, File as NodeFile } from 'node:buffer'
import { fireEvent, screen, waitFor, within } from '@testing-library/dom'
import { http, HttpResponse, type RequestHandler } from 'msw'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import type { CommitResponse, PreviewResponse, PreviewRow } from '@/shared/api/types'
import { renderRoute, testUser } from '@/test/render'

const api = `${window.location.origin}/api/v1`
const problem = (status: number, code: string, errors?: { field: string; code: string }[]) =>
  HttpResponse.json({ status, code, detail: 'raw', ...(errors ? { errors } : {}) }, { status })
const norm = (s: string | null | undefined) => (s ?? '').replace(/\s/g, ' ')

const account = {
  id: testUser.accountId,
  name: 'Ana e Bia',
  currency: 'EUR',
  members: [],
  pendingInvitation: null,
}

const cat = { color: '#0000aa', systemDefault: false, archived: false }
const mercado = { ...cat, id: 'c1', name: 'Mercado', kind: 'EXPENSE', icon: 'shopping-cart' }
const salario = { ...cat, id: 'c2', name: 'Salário', kind: 'INCOME', icon: 'banknote' }
const antiga = { ...cat, id: 'c3', name: 'Antiga', kind: 'EXPENSE', icon: 'gift', archived: true }
const lazer = { ...cat, id: 'c4', name: 'Lazer', kind: 'EXPENSE', icon: 'gift' }
const categories = [mercado, salario, antiga, lazer]

const bank = { type: 'CHECKING', initialBalanceCents: 0, balanceCents: 0, holderUserId: null }
const revolut = {
  ...bank,
  id: 'b1',
  name: 'Revolut',
  archived: false,
  createdAt: '',
  updatedAt: '',
}
const cofre = { ...revolut, id: 'b2', name: 'Cofre' }
const velha = { ...revolut, id: 'b3', name: 'Velha', archived: true }
const banks = [revolut, cofre, velha]

const row = (r: Partial<PreviewRow> & Pick<PreviewRow, 'rowId' | 'description'>): PreviewRow => ({
  date: '2026-11-05',
  kind: 'EXPENSE',
  amountCents: 1000,
  descriptionKey: r.description.toLowerCase(),
  bankAccountId: 'b1',
  destinationBankAccountId: null,
  suggestedCategoryId: 'c1',
  status: 'NEW',
  ...r,
})
const PINGO = 'Mercado Pingo Doce'
const baseRows: PreviewRow[] = [
  row({ rowId: 'r1', description: PINGO, amountCents: 2550 }),
  row({ rowId: 'r2', description: PINGO, amountCents: 1200 }),
  row({ rowId: 'r8', description: PINGO, amountCents: 800 }),
  row({ rowId: 'r5', description: PINGO, status: 'ALREADY_IMPORTED' }),
  row({
    rowId: 'r3',
    description: 'Salário',
    kind: 'INCOME',
    amountCents: 300000,
    suggestedCategoryId: 'c2',
  }),
  row({ rowId: 'r6', description: 'Farmácia', amountCents: 1500, status: 'POSSIBLE_DUPLICATE' }),
  row({
    rowId: 'r7',
    description: 'To Savings',
    kind: 'TRANSFER',
    amountCents: 5000,
    destinationBankAccountId: 'b2',
    suggestedCategoryId: null,
  }),
]
const lojaNova = row({ rowId: 'r4', description: 'Loja nova', suggestedCategoryId: null })

const preview = (over: Partial<PreviewResponse> = {}): PreviewResponse => ({
  products: [{ product: 'Current', bankAccountId: 'b1' }],
  rows: baseRows,
  skipped: {
    counts: { PENDING: 1, EXCHANGE: 1 },
    rows: [
      {
        lineNumber: 4,
        date: '2026-11-06',
        description: 'Padaria',
        amountRaw: '-3.20',
        currency: 'EUR',
        reason: 'PENDING',
      },
      {
        lineNumber: 7,
        date: '2026-11-07',
        description: 'Exchanged to USD',
        amountRaw: '-50.00',
        currency: 'EUR',
        reason: 'EXCHANGE',
      },
    ],
  },
  ...over,
})

const batch = (over: Record<string, unknown>) => ({
  source: 'REVOLUT',
  bankAccountId: 'b1',
  createdBy: testUser.id,
  createdByName: 'Ana Souza',
  createdAt: '2026-11-08T10:00:00Z',
  rowCount: 12,
  undoneAt: null,
  ...over,
})

interface Sent {
  bankAccountId: string | null
  fileName: string | null
  fileText: string | null
  productMapping: unknown
  decisions: unknown
}

async function readForm(request: Request): Promise<Sent> {
  const form = await request.formData()
  const file = form.get('file') as File | null
  const json = async (name: string) => {
    const part = form.get(name) as Blob | null
    return part ? JSON.parse(await part.text()) : undefined
  }
  return {
    bankAccountId: form.get('bankAccountId') as string | null,
    fileName: file?.name ?? null,
    fileText: file ? await file.text() : null,
    productMapping: await json('productMapping'),
    decisions: await json('decisions'),
  }
}

function setup(
  opts: {
    preview?: PreviewResponse | ((n: number, sent: Sent) => PreviewResponse)
    extra?: RequestHandler[]
    batches?: unknown[]
    commit?: CommitResponse
    /** Holds the n-th preview response (1-based) until the returned promise settles. */
    previewGate?: (n: number) => Promise<void> | undefined
    /** Holds the commit response until it settles. */
    commitGate?: Promise<void>
  } = {},
) {
  const previews: Sent[] = []
  const commits: Sent[] = []
  const undone: string[] = []
  const handlers = [
    ...(opts.extra ?? []),
    http.get(`${api}/account`, () => HttpResponse.json(account)),
    http.get(`${api}/categories`, ({ request }) => {
      const p = new URL(request.url).searchParams
      const kind = p.get('kind')
      const archived = p.get('includeArchived') === 'true'
      return HttpResponse.json(
        categories.filter((c) => (!kind || c.kind === kind) && (archived || !c.archived)),
      )
    }),
    http.get(`${api}/bank-accounts`, ({ request }) => {
      const archived = new URL(request.url).searchParams.get('includeArchived') === 'true'
      return HttpResponse.json(banks.filter((b) => archived || !b.archived))
    }),
    http.post(`${api}/imports/revolut/preview`, async ({ request }) => {
      const sent = await readForm(request)
      previews.push(sent)
      await opts.previewGate?.(previews.length)
      const p = opts.preview ?? preview()
      return HttpResponse.json(typeof p === 'function' ? p(previews.length, sent) : p)
    }),
    http.post(`${api}/imports/revolut/commit`, async ({ request }) => {
      commits.push(await readForm(request))
      await opts.commitGate
      return HttpResponse.json(
        opts.commit ?? { batchId: 'batch1', created: 5, skippedDuplicates: 2 },
      )
    }),
    http.get(`${api}/imports`, () => {
      const content = opts.batches ?? []
      return HttpResponse.json({
        content,
        page: 0,
        size: 20,
        totalElements: content.length,
        totalPages: 1,
      })
    }),
    http.post(`${api}/imports/:id/undo`, ({ params }) => {
      undone.push(params.id as string)
      return new HttpResponse(null, { status: 204 })
    }),
  ]
  return {
    previews,
    commits,
    undone,
    render: () => renderRoute({ route: '/transactions/import', session: {}, handlers }),
  }
}

/** A statement with a Savings product besides Current, mapped as the request asked. */
const withSavings = (_n: number, sent: Sent) =>
  preview({
    products: [
      { product: 'Current', bankAccountId: 'b1' },
      {
        product: 'Savings',
        bankAccountId: (sent.productMapping as Record<string, string> | undefined)?.Savings ?? null,
      },
    ],
  })

const deferred = () => {
  let release!: () => void
  const promise = new Promise<void>((r) => (release = r))
  return { promise, release }
}

const csv = () => new File(['Type,Product\n'], 'extrato.csv', { type: 'text/csv' })

const optionTexts = (select: HTMLElement) =>
  Array.from((select as HTMLSelectElement).options).map((o) => o.text.trim())

/** Renders the page and runs the first preview of `extrato.csv` into Revolut. */
async function upload(
  s: ReturnType<typeof setup>,
  rendered?: Awaited<ReturnType<ReturnType<typeof setup>['render']>>,
) {
  const r = rendered ?? (await s.render())
  const { user } = r
  const destination = screen.getByLabelText('Conta de destino')
  await waitFor(() => expect(optionTexts(destination)).toContain('Revolut'))
  await user.selectOptions(destination, 'b1')
  await user.upload(screen.getByLabelText('Arquivo CSV'), csv())
  await user.click(screen.getByRole('button', { name: 'Revisar' }))
  return r
}

const panel = () => screen.getByRole('tabpanel')
const rowsIn = (el: HTMLElement) => within(el).getAllByRole('listitem')
const submitButton = () => screen.getByRole('button', { name: /^Importar \d+ lançamentos?$/ })

// jsdom's FormData, Blob and File cannot be sent by Node's fetch (the body length comes out
// wrong), so uploads use Node's own classes; Response.formData() hands back Node's FormData.
beforeEach(async () => {
  const NodeFormData = (await new Response(new URLSearchParams('a=b')).formData()).constructor
  vi.stubGlobal('FormData', NodeFormData)
  vi.stubGlobal('Blob', NodeBlob)
  vi.stubGlobal('File', NodeFile)
})
afterEach(() => {
  vi.unstubAllGlobals()
})

describe('ImportPage', () => {
  it('upload_showsReviewWithTabsAndSkippedReasons', async () => {
    const s = setup()
    const rendered = await s.render()
    expect(
      screen.getByRole('heading', { level: 1, name: 'Importar extrato da Revolut' }),
    ).toBeTruthy()
    expect(screen.getByText('Como exportar da Revolut')).toBeTruthy()
    expect(
      screen.getByText(
        'No app da Revolut: Conta → Extrato → Excel/CSV → escolha o período → Gerar',
      ),
    ).toBeTruthy()
    const destination = screen.getByLabelText('Conta de destino')
    await waitFor(() => expect(optionTexts(destination)).toContain('Revolut'))
    expect(optionTexts(destination).join()).not.toContain('Velha')

    const { user } = await upload(s, rendered)
    await screen.findByRole('tab', { name: /Novas/ })
    expect(s.previews).toHaveLength(1)
    expect(s.previews[0]).toMatchObject({
      bankAccountId: 'b1',
      fileName: 'extrato.csv',
      fileText: 'Type,Product\n',
      productMapping: undefined,
    })

    const news = rowsIn(panel())
    // NEW rows plus the locked ALREADY_IMPORTED one
    expect(news).toHaveLength(6)
    const locked = news.find((li) => li.textContent?.includes('Já importada'))!
    const lockedBox = within(locked).getByRole('checkbox') as HTMLInputElement
    expect(lockedBox.disabled).toBe(true)
    expect(lockedBox.checked).toBe(false)
    const transfer = news.find((li) => li.textContent?.includes('To Savings'))!
    expect(norm(transfer.textContent)).toContain('Revolut → Cofre')
    expect(within(transfer).queryByRole('combobox')).toBeNull()

    // r1 + r2 + r8 expenses, r3 income, the transfer counted but in neither total
    const footer = screen.getByRole('region', { name: 'Resumo da importação' })
    expect(norm(footer.textContent)).toContain('5 linhas incluídas')
    expect(norm(footer.textContent)).toContain('+€ 3.000,00')
    expect(norm(footer.textContent)).toContain('€ 45,50')
    expect(submitButton().textContent?.trim()).toBe('Importar 5 lançamentos')

    await user.click(screen.getByRole('tab', { name: /Puladas/ }))
    const skipped = rowsIn(panel())
    expect(skipped).toHaveLength(2)
    expect(skipped[0]!.textContent).toContain('Padaria')
    expect(skipped[0]!.textContent).toContain('Pendente')
    expect(skipped[0]!.textContent).toContain('-3.20 EUR')
    expect(skipped[1]!.textContent).toContain('Câmbio')
    expect(within(panel()).queryByRole('checkbox')).toBeNull()
  })

  it('upload_otherProducts_showsMappingAndRepreviews', async () => {
    const s = setup({ preview: withSavings })
    const { user } = await upload(s)
    const savings = (await screen.findByLabelText('Savings')) as HTMLSelectElement
    expect(optionTexts(savings)).toEqual(['Ignorar', 'Revolut', 'Cofre'])
    expect(savings.value).toBe('')
    expect(screen.queryByLabelText('Current')).toBeNull()

    await user.selectOptions(savings, 'b2')
    await waitFor(() => expect(s.previews).toHaveLength(2))
    expect(s.previews[1]).toMatchObject({ bankAccountId: 'b1', productMapping: { Savings: 'b2' } })
    await waitFor(() =>
      expect((screen.getByLabelText('Savings') as HTMLSelectElement).value).toBe('b2'),
    )

    // the commit sends the same mapping as the preview it reviewed
    await user.click(submitButton())
    await waitFor(() => expect(s.commits).toHaveLength(1))
    expect(s.commits[0]!.productMapping).toEqual({ Savings: 'b2' })
  })

  it('upload_formatError_showsNotRevolutMessage', async () => {
    const s = setup({
      extra: [
        http.post(`${api}/imports/revolut/preview`, () =>
          problem(422, 'IMPORT_FORMAT_UNSUPPORTED'),
        ),
      ],
    })
    await upload(s)
    expect((await screen.findByRole('alert')).textContent).toContain(
      'Este arquivo não parece ser um extrato CSV da Revolut.',
    )
    expect(screen.queryByRole('tab')).toBeNull()
  })

  it('upload_rowInvalid_showsLineNumber', async () => {
    const s = setup({
      extra: [
        http.post(`${api}/imports/revolut/preview`, () =>
          HttpResponse.json(
            { status: 422, code: 'IMPORT_ROW_INVALID', detail: 'line 3', line: 3 },
            { status: 422 },
          ),
        ),
      ],
    })
    await upload(s)
    expect((await screen.findByRole('alert')).textContent).toContain(
      'O arquivo tem uma linha com data ou valor ilegível (linha 3).',
    )
  })

  it('upload_413_showsTooLargeMessage', async () => {
    const s = setup({
      extra: [
        http.post(
          `${api}/imports/revolut/preview`,
          () =>
            new HttpResponse('<html><body>413 Request Entity Too Large</body></html>', {
              status: 413,
              headers: { 'Content-Type': 'text/html' },
            }),
        ),
      ],
    })
    await upload(s)
    expect((await screen.findByRole('alert')).textContent).toContain(
      'O arquivo é grande demais (máximo de 1 MB).',
    )
  })

  it('review_changeCategory_offersApplyToSameDescription', async () => {
    const s = setup()
    const { user } = await upload(s)
    await screen.findByRole('tab', { name: /Novas/ })
    const pingo = rowsIn(panel()).filter(
      (li) => li.textContent?.includes(PINGO) && !li.textContent.includes('Já importada'),
    )
    expect(pingo).toHaveLength(3)
    const select = (li: HTMLElement) =>
      within(li).getByRole('combobox', { name: `Categoria de ${PINGO}` }) as HTMLSelectElement
    await waitFor(() => expect(optionTexts(select(pingo[0]!))).toContain('Lazer'))
    const options = optionTexts(select(pingo[0]!))
    expect(options.join()).not.toContain('Antiga')
    expect(options.join()).not.toContain('Salário')

    await user.selectOptions(select(pingo[0]!), 'c4')
    // the locked ALREADY_IMPORTED row of the same description is not counted
    expect(within(pingo[0]!).getByText('Aplicar às outras 2 linhas iguais?')).toBeTruthy()
    expect(select(pingo[0]!).value).toBe('c4')
    expect(select(pingo[1]!).value).toBe('c1')

    await user.click(within(pingo[0]!).getByRole('button', { name: 'Aplicar' }))
    expect(select(pingo[1]!).value).toBe('c4')
    expect(select(pingo[2]!).value).toBe('c4')
    expect(screen.queryByText(/Aplicar às outras/)).toBeNull()

    await user.click(submitButton())
    await waitFor(() => expect(s.commits).toHaveLength(1))
    const decisions = s.commits[0]!.decisions as { rowId: string; categoryId: string | null }[]
    expect(decisions.filter((d) => ['r1', 'r2', 'r8'].includes(d.rowId))).toEqual([
      { rowId: 'r1', include: true, categoryId: 'c4' },
      { rowId: 'r2', include: true, categoryId: 'c4' },
      { rowId: 'r8', include: true, categoryId: 'c4' },
    ])
  })

  it('review_submitDisabledWhileIncludedRowLacksCategory', async () => {
    const s = setup({ preview: preview({ rows: [...baseRows, lojaNova] }) })
    const { user } = await upload(s)
    await screen.findByRole('tab', { name: /Novas/ })
    expect(submitButton().textContent?.trim()).toBe('Importar 6 lançamentos')
    expect((submitButton() as HTMLButtonElement).disabled).toBe(true)

    const loja = rowsIn(panel()).find((li) => li.textContent?.includes('Loja nova'))!
    const include = within(loja).getByRole('checkbox', { name: 'Incluir Loja nova' })
    await user.click(include)
    expect(submitButton().textContent?.trim()).toBe('Importar 5 lançamentos')
    expect((submitButton() as HTMLButtonElement).disabled).toBe(false)

    await user.click(include)
    expect((submitButton() as HTMLButtonElement).disabled).toBe(true)
    const category = within(loja).getByRole('combobox', { name: 'Categoria de Loja nova' })
    await waitFor(() => expect(optionTexts(category)).toContain('Mercado'))
    await user.selectOptions(category, 'c1')
    expect((submitButton() as HTMLButtonElement).disabled).toBe(false)
  })

  it('review_possibleDuplicatesStartUnchecked', async () => {
    const s = setup()
    const { user } = await upload(s)
    await user.click(await screen.findByRole('tab', { name: /Possíveis duplicatas/ }))
    const [farmacia] = rowsIn(panel())
    const box = within(farmacia!).getByRole('checkbox', {
      name: 'Incluir Farmácia',
    }) as HTMLInputElement
    expect(box.checked).toBe(false)
    await user.click(box)
    expect(submitButton().textContent?.trim()).toBe('Importar 6 lançamentos')

    await user.click(submitButton())
    await waitFor(() => expect(s.commits).toHaveLength(1))
    expect(s.commits[0]).toMatchObject({ bankAccountId: 'b1', fileName: 'extrato.csv' })
    const decisions = s.commits[0]!.decisions as { rowId: string }[]
    expect(decisions).toContainEqual({ rowId: 'r6', include: true, categoryId: 'c1' })
    expect(decisions).toContainEqual({ rowId: 'r7', include: true, categoryId: null })
    expect(decisions.map((d) => d.rowId)).not.toContain('r5')
  })

  it('commit_sendsDecisionsOnlyForIncludedRows', async () => {
    // the unchecked possible duplicate r6 and the locked r5 get no decision: missing means "not imported"
    const s = setup()
    const { user } = await upload(s)
    await screen.findByRole('tab', { name: /Novas/ })
    await user.click(submitButton())
    await waitFor(() => expect(s.commits).toHaveLength(1))
    const decisions = s.commits[0]!.decisions as { rowId: string; include: boolean }[]
    expect(decisions.map((d) => d.rowId).sort()).toEqual(['r1', 'r2', 'r3', 'r7', 'r8'])
    expect(decisions.every((d) => d.include)).toBe(true)
  })

  it('commit_413_showsTooLargeMessage', async () => {
    const s = setup({
      extra: [
        http.post(
          `${api}/imports/revolut/commit`,
          () =>
            new HttpResponse('<html><body>413 Request Entity Too Large</body></html>', {
              status: 413,
              headers: { 'Content-Type': 'text/html' },
            }),
        ),
      ],
    })
    const { user } = await upload(s)
    await screen.findByRole('tab', { name: /Novas/ })
    await user.click(submitButton())
    expect((await screen.findByRole('alert')).textContent).toContain(
      'O arquivo é grande demais (máximo de 1 MB).',
    )
  })

  it('review_rowUnknownError_asksToReloadPreview', async () => {
    const s = setup({
      extra: [
        http.post(`${api}/imports/revolut/commit`, () =>
          problem(400, 'VALIDATION_FAILED', [{ field: 'decisions[r1]', code: 'ROW_UNKNOWN' }]),
        ),
      ],
    })
    const { user } = await upload(s)
    await screen.findByRole('tab', { name: /Novas/ })
    await user.click(submitButton())
    expect((await screen.findByRole('alert')).textContent).toContain(
      'O arquivo mudou desde a revisão. Envie-o de novo para revisar.',
    )
    // back to the upload step, the file has to be chosen again
    expect(screen.queryByRole('tab')).toBeNull()
    expect((screen.getByLabelText('Arquivo CSV') as HTMLInputElement).files).toHaveLength(0)
    expect((screen.getByRole('button', { name: 'Revisar' }) as HTMLButtonElement).disabled).toBe(
      true,
    )

    await user.upload(screen.getByLabelText('Arquivo CSV'), csv())
    await user.click(screen.getByRole('button', { name: 'Revisar' }))
    await screen.findByRole('tab', { name: /Novas/ })
    expect(s.previews).toHaveLength(2)
  })

  const rowCommitError = (rowId: string, code: string) =>
    http.post(`${api}/imports/revolut/commit`, () =>
      problem(400, 'VALIDATION_FAILED', [{ field: `decisions[${rowId}]`, code }]),
    )
  const descriptionOf = (el: HTMLElement) =>
    (el.getAttribute('aria-describedby') ?? '')
      .split(' ')
      .map((id) => document.getElementById(id)?.textContent?.trim() ?? '')
      .join(' ')

  it('commit_categoryArchived_marksRowAndKeepsReview', async () => {
    const s = setup({ extra: [rowCommitError('r1', 'CATEGORY_ARCHIVED')] })
    const { user } = await upload(s)
    await screen.findByRole('tab', { name: /Novas/ })
    await user.click(submitButton())

    expect((await screen.findByRole('alert')).textContent).toContain('1 linha precisa de correção.')
    const [first, second] = rowsIn(panel())
    const category = within(first!).getByRole('combobox', {
      name: `Categoria de ${PINGO}`,
    }) as HTMLSelectElement
    expect(category.getAttribute('aria-invalid')).toBe('true')
    expect(descriptionOf(category)).toBe('Esta categoria foi arquivada. Escolha outra.')
    const other = within(second!).getByRole('combobox', { name: `Categoria de ${PINGO}` })
    expect(other.getAttribute('aria-invalid')).toBeNull()
    expect((other as HTMLSelectElement).value).toBe('c1')
    expect(screen.getByRole('tab', { name: /Novas/ })).toBeTruthy()
  })

  it('commit_categoryArchived_reloadsCategoriesWithoutTheArchivedOne', async () => {
    // the partner archived Mercado while this review was open; the server now lists it as archived
    let mercadoArchived = false
    const s = setup({
      extra: [
        http.get(`${api}/categories`, ({ request }) => {
          const kind = new URL(request.url).searchParams.get('kind')
          const now = categories.map((c) =>
            c.id === 'c1' ? { ...c, archived: mercadoArchived } : c,
          )
          return HttpResponse.json(now.filter((c) => (!kind || c.kind === kind) && !c.archived))
        }),
        http.post(`${api}/imports/revolut/commit`, () => {
          mercadoArchived = true
          return problem(400, 'VALIDATION_FAILED', [
            { field: 'decisions[r1]', code: 'CATEGORY_ARCHIVED' },
          ])
        }),
      ],
    })
    const { user } = await upload(s)
    await screen.findByRole('tab', { name: /Novas/ })
    await user.click(submitButton())
    await screen.findByText('Esta categoria foi arquivada. Escolha outra.')

    const [first] = rowsIn(panel())
    const select = within(first!).getByRole('combobox', { name: `Categoria de ${PINGO}` })
    await waitFor(() => expect(optionTexts(select)).not.toContain('Mercado'))
    expect(optionTexts(select)).toContain('Lazer')
  })

  it('commit_rowError_clearsWhenCategoryChanges', async () => {
    const s = setup({ extra: [rowCommitError('r1', 'CATEGORY_ARCHIVED')] })
    const { user } = await upload(s)
    await screen.findByRole('tab', { name: /Novas/ })
    await user.click(submitButton())
    await screen.findByText('Esta categoria foi arquivada. Escolha outra.')

    const [first] = rowsIn(panel())
    await user.selectOptions(
      within(first!).getByRole('combobox', { name: `Categoria de ${PINGO}` }),
      'c4',
    )

    expect(screen.queryByText('Esta categoria foi arquivada. Escolha outra.')).toBeNull()
    expect(
      within(first!)
        .getByRole('combobox', { name: `Categoria de ${PINGO}` })
        .getAttribute('aria-invalid'),
    ).toBeNull()
  })

  it('commit_rowErrorOnDuplicate_switchesToDuplicatesTab', async () => {
    const s = setup({ extra: [rowCommitError('r6', 'ROW_ALREADY_IMPORTED')] })
    const { user } = await upload(s)
    await user.click(await screen.findByRole('tab', { name: /Possíveis duplicatas/ }))
    await user.click(
      within(rowsIn(panel())[0]!).getByRole('checkbox', { name: 'Incluir Farmácia' }),
    )
    await user.click(screen.getByRole('tab', { name: /Novas/ }))
    await user.click(submitButton())

    await screen.findByText('Esta linha já foi importada. Desmarque-a para continuar.')
    expect(
      screen.getByRole('tab', { name: /Possíveis duplicatas/ }).getAttribute('aria-selected'),
    ).toBe('true')
    const box = within(rowsIn(panel())[0]!).getByRole('checkbox', { name: 'Incluir Farmácia' })
    expect(box.getAttribute('aria-invalid')).toBe('true')
  })

  it('result_showsCreatedAndAlreadyExisting_andUndoAsksConfirmation', async () => {
    const s = setup()
    const { user } = await upload(s)
    await screen.findByRole('tab', { name: /Novas/ })
    await user.click(submitButton())
    expect(await screen.findByText('5 lançamentos importados, 2 já existiam')).toBeTruthy()
    expect(screen.queryByRole('tab')).toBeNull()
    expect(screen.getByRole('link', { name: 'Ver lançamentos' }).getAttribute('href')).toBe(
      '/transactions',
    )

    await user.click(screen.getByRole('button', { name: 'Desfazer importação' }))
    const dialog = await screen.findByRole('dialog', { name: 'Desfazer esta importação?' })
    expect(dialog.textContent).toContain(
      'Os lançamentos dela serão excluídos, inclusive os que você editou depois.',
    )
    expect(s.undone).toEqual([])
    await user.click(within(dialog).getByRole('button', { name: 'Desfazer' }))
    await waitFor(() => expect(s.undone).toEqual(['batch1']))
    expect(await screen.findByText('Importação desfeita.')).toBeTruthy()
    expect(screen.queryByRole('button', { name: 'Desfazer importação' })).toBeNull()
  })

  it('upload_changeFileWhilePreviewPending_discardsStalePreview', async () => {
    const first = deferred()
    const s = setup({ previewGate: (n) => (n === 1 ? first.promise : undefined) })
    const { user } = await upload(s)
    await waitFor(() => expect(s.previews).toHaveLength(1))
    const other = new File(['Type,Product,Other\n'], 'outro.csv', { type: 'text/csv' })
    await user.upload(screen.getByLabelText('Arquivo CSV'), other)
    first.release()
    // the response for the old file lands, and is dropped
    await waitFor(() =>
      expect((screen.getByRole('button', { name: 'Revisar' }) as HTMLButtonElement).disabled).toBe(
        false,
      ),
    )
    expect(screen.queryByRole('tab')).toBeNull()

    await user.click(screen.getByRole('button', { name: 'Revisar' }))
    await screen.findByRole('tab', { name: /Novas/ })
    await user.click(submitButton())
    await waitFor(() => expect(s.commits).toHaveLength(1))
    expect(s.commits[0]).toMatchObject({ fileName: 'outro.csv', fileText: 'Type,Product,Other\n' })
  })

  it('review_uploadControlsDisabledWhileCommitPending', async () => {
    const commit = deferred()
    const s = setup({ preview: withSavings, commitGate: commit.promise })
    const { user } = await upload(s)
    await screen.findByLabelText('Savings')
    await user.click(submitButton())
    await waitFor(() => expect(s.commits).toHaveLength(1))
    const disabled = (label: string) =>
      (screen.getByLabelText(label) as HTMLInputElement | HTMLSelectElement).disabled
    expect(disabled('Conta de destino')).toBe(true)
    expect(disabled('Arquivo CSV')).toBe(true)
    expect(disabled('Savings')).toBe(true)
    expect((submitButton() as HTMLButtonElement).disabled).toBe(true)

    commit.release()
    expect(await screen.findByText('5 lançamentos importados, 2 já existiam')).toBeTruthy()
    expect(s.previews).toHaveLength(1)
  })

  it('review_revisarNotAvailableDuringReview', async () => {
    const s = setup()
    await upload(s)
    await screen.findByRole('tab', { name: /Novas/ })
    expect(screen.queryByRole('button', { name: 'Revisar' })).toBeNull()
    // nor by submitting the form some other way (Enter)
    fireEvent.submit(screen.getByLabelText('Conta de destino').closest('form')!)
    await new Promise((r) => setTimeout(r, 50))
    expect(s.previews).toHaveLength(1)
  })

  it('review_remapResetsCategoryEdits', async () => {
    const s = setup({ preview: withSavings })
    const { user } = await upload(s)
    await screen.findByRole('tab', { name: /Novas/ })
    const firstPingo = () =>
      within(panel()).getAllByRole('combobox', {
        name: `Categoria de ${PINGO}`,
      })[0] as HTMLSelectElement
    await waitFor(() => expect(optionTexts(firstPingo())).toContain('Lazer'))
    await user.selectOptions(firstPingo(), 'c4')
    expect(firstPingo().value).toBe('c4')

    await user.selectOptions(screen.getByLabelText('Savings'), 'b2')
    await waitFor(() => expect(s.previews).toHaveLength(2))
    // a new plan: the review starts again from the suggestions
    await waitFor(() => expect(firstPingo().value).toBe('c1'))
    expect(screen.queryByText(/Aplicar às outras/)).toBeNull()
  })

  it('result_singularCounts', async () => {
    const s = setup({ commit: { batchId: 'batch1', created: 1, skippedDuplicates: 1 } })
    const { user } = await upload(s)
    await screen.findByRole('tab', { name: /Novas/ })
    await user.click(submitButton())
    expect(await screen.findByText('1 lançamento importado, 1 já existia')).toBeTruthy()
  })

  it('review_focusesHeadingWhenShown', async () => {
    const s = setup()
    await upload(s)
    const heading = await screen.findByRole('heading', { name: 'Revisar lançamentos' })
    await waitFor(() => expect(document.activeElement).toBe(heading))
  })

  it('result_focusesHeadingWhenShown', async () => {
    const s = setup()
    const { user } = await upload(s)
    await screen.findByRole('tab', { name: /Novas/ })
    await user.click(submitButton())
    const heading = await screen.findByRole('heading', { name: 'Importação concluída' })
    await waitFor(() => expect(document.activeElement).toBe(heading))
  })

  it('review_summaryCountIsLive', async () => {
    const s = setup()
    await upload(s)
    const count = await screen.findByText('5 linhas incluídas')
    expect(count.getAttribute('aria-live')).toBe('polite')
  })

  it('review_applyPromptIsAnnouncedGroup', async () => {
    const s = setup()
    const { user } = await upload(s)
    await screen.findByRole('tab', { name: /Novas/ })
    const [first] = rowsIn(panel())
    const select = within(first!).getByRole('combobox', { name: `Categoria de ${PINGO}` })
    await waitFor(() => expect(optionTexts(select)).toContain('Lazer'))
    await user.selectOptions(select, 'c4')

    const group = within(first!).getByRole('group', { name: 'Aplicar às outras 2 linhas iguais?' })
    expect(group.getAttribute('aria-live')).toBe('polite')
  })

  it('history_listsBatchesWithUndoOnlyForActive', async () => {
    const s = setup({
      batches: [
        batch({ id: 'bA' }),
        batch({
          id: 'bB',
          createdBy: null,
          createdByName: null,
          rowCount: 3,
          createdAt: '2026-11-01T10:00:00Z',
          undoneAt: '2026-11-02T10:00:00Z',
        }),
      ],
    })
    const { user } = await s.render()
    const list = await screen.findByRole('list', { name: 'Importações anteriores' })
    const [active, undone] = within(list).getAllByRole('listitem')
    expect(active!.textContent).toContain('12 lançamentos')
    expect(active!.textContent).toContain('Revolut')
    expect(within(active!).getByRole('img', { name: 'Ana Souza' })).toBeTruthy()
    expect(undone!.textContent).toContain('3 lançamentos')
    expect(undone!.textContent).toContain('Desfeita')
    expect(within(undone!).getByRole('img', { name: 'Ex-membro' })).toBeTruthy()
    expect(within(undone!).queryByRole('button')).toBeNull()

    await user.click(within(active!).getByRole('button', { name: /^Desfazer importação de / }))
    const dialog = await screen.findByRole('dialog', { name: 'Desfazer esta importação?' })
    await user.click(within(dialog).getByRole('button', { name: 'Desfazer' }))
    await waitFor(() => expect(s.undone).toEqual(['bA']))
  })
})
