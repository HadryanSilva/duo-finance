import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  addMonths,
  currentMonth,
  formatBusinessDate,
  formatMonth,
  groupByDate,
  todayIso,
} from './dates'

// vite.config.ts runs this file twice, under America/Sao_Paulo and Asia/Tokyo.
describe('business dates', () => {
  afterEach(() => vi.useRealTimers())

  it('runs under the TZ configured by the project', () => {
    expect(process.env.TZ).toBeTruthy()
    expect(Intl.DateTimeFormat().resolvedOptions().timeZone).toBe(process.env.TZ)
  })

  it('formatBusinessDate_isTimezoneIndependent', () => {
    const long = formatBusinessDate('2026-03-31', 'en', 'long')
    expect(long).toContain('31')
    expect(long).toContain('March')
    expect(formatBusinessDate('2026-03-31', 'pt-BR', 'long')).toContain('março')
    expect(formatBusinessDate('2026-03-31', 'pt-BR')).toBe('31/03/2026')
    expect(formatBusinessDate('2026-03-31', 'en')).toBe('3/31/2026')
  })

  it('formatMonth keeps the month', () => {
    expect(formatMonth('2026-03', 'en')).toBe('March 2026')
    expect(formatMonth('2026-03', 'pt-BR')).toBe('março de 2026')
  })

  it('todayIso and currentMonth use the local calendar day', () => {
    vi.useFakeTimers()
    vi.setSystemTime(new Date(2026, 2, 31, 23, 30))
    expect(todayIso()).toBe('2026-03-31')
    expect(currentMonth()).toBe('2026-03')
    vi.setSystemTime(new Date(2026, 0, 1, 0, 5))
    expect(todayIso()).toBe('2026-01-01')
  })

  it('addMonths crosses years', () => {
    expect(addMonths('2026-12', 1)).toBe('2027-01')
    expect(addMonths('2026-01', -1)).toBe('2025-12')
    expect(addMonths('2026-03', 0)).toBe('2026-03')
    expect(addMonths('2026-03', 25)).toBe('2028-04')
  })

  it('groupByDate preserves input order', () => {
    const rows = [
      { date: '2026-03-02', id: 1 },
      { date: '2026-03-01', id: 2 },
      { date: '2026-03-02', id: 3 },
    ]
    expect(groupByDate(rows)).toEqual([
      { date: '2026-03-02', rows: [rows[0], rows[2]] },
      { date: '2026-03-01', rows: [rows[1]] },
    ])
    expect(groupByDate([])).toEqual([])
  })
})
