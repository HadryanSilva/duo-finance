import { describe, expect, it } from 'vitest'
import { formatMoney, parseMoney } from './money'

// Node 24 ICU renders the pt-BR EUR suffix after U+00A0 (no-break space); en has no space.
// The readable assertions normalise any Unicode space; one test pins the exact code point.
const plain = (s: string) => s.replace(/\s/g, ' ')

describe('formatMoney', () => {
  it('uses locale separators', () => {
    expect(plain(formatMoney(123456, 'pt-BR'))).toBe('€ 1.234,56')
    expect(formatMoney(123456, 'en')).toBe('€1,234.56')
  })
  it('puts the pt-BR symbol first, followed by U+00A0 (Node 24 ICU)', () => {
    expect(formatMoney(123456, 'pt-BR')).toBe('€ 1.234,56')
  })
  it('formats zero and negatives', () => {
    expect(formatMoney(0, 'en')).toBe('€0.00')
    expect(formatMoney(-5, 'en')).toBe('-€0.05')
  })
})

describe('parseMoney', () => {
  it('parseMoney_ptBR', () => {
    expect(parseMoney('1.234,56', 'pt-BR')).toBe(123456)
    expect(parseMoney('12,5', 'pt-BR')).toBe(1250)
    expect(parseMoney('12', 'pt-BR')).toBe(1200)
    expect(parseMoney('1.234', 'pt-BR')).toBe(123400)
    expect(parseMoney(',5', 'pt-BR')).toBe(50)
    expect(parseMoney('0,05', 'pt-BR')).toBe(5)
  })
  it('parseMoney_en', () => {
    expect(parseMoney('1,234.56', 'en')).toBe(123456)
    expect(parseMoney('12.5', 'en')).toBe(1250)
    expect(parseMoney('1,234', 'en')).toBe(123400)
  })
  it('parseMoney_rejectsInvalid', () => {
    const bad = [
      '',
      '   ',
      'abc',
      '1,234,5',
      '1.2.3',
      '12,345',
      '-5',
      '1.23,4.5',
      '1.2345,00',
      ',',
      '€',
      '1,',
      '--5',
    ]
    for (const s of bad) expect(parseMoney(s, 'pt-BR'), s).toBeNull()
    expect(parseMoney('1,234.567', 'en')).toBeNull()
    expect(parseMoney('12.345', 'en')).toBeNull()
  })
  it('trims whitespace and accepts a euro sign on either side', () => {
    expect(parseMoney('  12,50  ', 'pt-BR')).toBe(1250)
    expect(parseMoney('€ 12,50', 'pt-BR')).toBe(1250)
    expect(parseMoney('12,50 €', 'pt-BR')).toBe(1250)
    expect(parseMoney('€1,234.56', 'en')).toBe(123456)
  })
  it('parseMoney_exactCents', () => {
    expect(parseMoney('0,1', 'pt-BR')! + parseMoney('0,2', 'pt-BR')!).toBe(30)
    expect(parseMoney('1.005,29', 'pt-BR')).toBe(100529)
  })
  it('parseMoney_negativeWhenAllowed', () => {
    expect(parseMoney('-50,00', 'pt-BR', { allowNegative: true })).toBe(-5000)
    expect(parseMoney('-0,05', 'pt-BR', { allowNegative: true })).toBe(-5)
    expect(parseMoney('-', 'pt-BR', { allowNegative: true })).toBeNull()
  })
})
