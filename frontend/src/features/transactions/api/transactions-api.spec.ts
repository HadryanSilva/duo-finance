import { describe, expect, it } from 'vitest'
import { activeFilterCount, filterFromQuery } from './transactions-api'

describe('activeFilterCount', () => {
  it('counts nothing for an empty filter', () => {
    expect(activeFilterCount({})).toBe(0)
  })

  it('leaves the search out', () => {
    expect(activeFilterCount({ q: 'mer' })).toBe(0)
  })

  it('counts each date bound', () => {
    expect(activeFilterCount({ from: '2026-11-01', to: '2026-11-30' })).toBe(2)
  })

  it('counts all six sheet filters', () => {
    expect(
      activeFilterCount({
        from: '2026-11-01',
        to: '2026-11-30',
        kind: 'EXPENSE',
        categoryId: 'c1',
        bankAccountId: 'b1',
        createdBy: 'u1',
        q: 'mer',
      }),
    ).toBe(6)
  })
})

describe('filterFromQuery', () => {
  it('drops a category filter when the kind is TRANSFER', () => {
    const filter = filterFromQuery({ kind: 'TRANSFER', categoryId: 'c1' })
    expect(filter.kind).toBe('TRANSFER')
    expect(filter.categoryId).toBeUndefined()
  })

  it('keeps a category filter for expenses', () => {
    expect(filterFromQuery({ kind: 'EXPENSE', categoryId: 'c1' }).categoryId).toBe('c1')
  })
})
