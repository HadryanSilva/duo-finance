import { screen } from '@testing-library/dom'
import { describe, expect, it } from 'vitest'
import { formatBusinessDate } from '@/shared/lib/dates'
import { render } from '@/test/render'
import type { StaleImport } from '../lib/figures'
import StaleImportsNotice from './StaleImportsNotice.vue'

const norm = (s: string | null) => (s ?? '').replace(/\s/g, ' ')
const items: StaleImport[] = [
  { id: 'a1', name: 'Revolut', lastImportedDate: '2026-11-03', days: 18 },
  { id: 'a2', name: 'Wise', lastImportedDate: '2026-11-10', days: 11 },
]

describe('StaleImportsNotice', () => {
  it('rendersOneLinePerAccount_withLink', async () => {
    await render(StaleImportsNotice, {}, { props: { items } })
    const rows = screen.getAllByRole('listitem')
    expect(rows).toHaveLength(2)
    expect(norm(rows[0]!.textContent)).toContain(
      `Revolut: lançamentos até ${formatBusinessDate('2026-11-03', 'pt-BR')} (há 18 dias).`,
    )
    // each link names its account for screen readers, while the visible text stays short
    const revolut = screen.getByRole('link', { name: 'Importar extrato da conta Revolut' })
    const wise = screen.getByRole('link', { name: 'Importar extrato da conta Wise' })
    expect(revolut.textContent?.trim()).toBe('Importar extrato')
    expect(revolut.getAttribute('href')).toBe('/transactions/import?bankAccountId=a1')
    expect(wise.getAttribute('href')).toBe('/transactions/import?bankAccountId=a2')
  })

  it('empty_rendersNothing', async () => {
    await render(StaleImportsNotice, {}, { props: { items: [] } })
    expect(screen.queryByRole('status')).toBeNull()
  })

  it('hasNoCloseButton', async () => {
    await render(StaleImportsNotice, {}, { props: { items } })
    expect(screen.queryByRole('button')).toBeNull()
  })
})
