import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { afterEach, describe, expect, it } from 'vitest'
import { ApiError } from '../api/problem'
import { CLIENT_ERROR_CODES, ERROR_CODES, errorMessage } from './error-codes'
import { detectLocale, i18n, setLocale } from './index'
import en from './locales/en.json'
import ptBR from './locales/pt-BR.json'

type Tree = { [k: string]: string | Tree }
function flatKeys(o: Tree, prefix = ''): string[] {
  return Object.entries(o)
    .flatMap(([k, v]) => (typeof v === 'string' ? [prefix + k] : flatKeys(v, `${prefix}${k}.`)))
    .sort()
}

describe('error codes', () => {
  it('ERROR_CODES has no duplicates and 33 entries', () => {
    expect(new Set(ERROR_CODES).size).toBe(ERROR_CODES.length)
    expect(ERROR_CODES).toHaveLength(33)
  })

  it('matches the backend ErrorCode enum exactly', () => {
    // vitest runs with cwd = frontend/
    const file = resolve(
      process.cwd(),
      '../backend/src/main/java/br/dev/duofinance/shared/error/ErrorCode.java',
    )
    const src = readFileSync(file, 'utf8')
    const start = src.indexOf('{', src.indexOf('enum ErrorCode'))
    const body = src.slice(start + 1, src.indexOf(';', start))
    const backend = [...body.matchAll(/^\s*([A-Z][A-Z0-9_]*)\(/gm)].map((m) => m[1])
    expect(backend.length).toBeGreaterThan(0)
    expect([...ERROR_CODES].sort()).toEqual([...backend].sort())
  })
})

describe('translations', () => {
  it('every error code has a translation in both locales', () => {
    for (const c of [...ERROR_CODES, ...CLIENT_ERROR_CODES]) {
      expect((ptBR.errors as Record<string, string>)[c], c).toBeTruthy()
      expect((en.errors as Record<string, string>)[c], c).toBeTruthy()
    }
  })
  it('both locales have the same keys', () => {
    expect(flatKeys(ptBR as Tree)).toEqual(flatKeys(en as Tree))
  })
  it('has validation messages for the field codes in use', () => {
    const codes = [
      'NotNull',
      'NotBlank',
      'Size',
      'Email',
      'Pattern',
      'Min',
      'Max',
      'AssertTrue',
      'OutOfRange',
      'NotAllowed',
      'Invalid',
    ]
    for (const c of codes) {
      expect((ptBR.validation as Record<string, string>)[c], c).toBeTruthy()
      expect((en.validation as Record<string, string>)[c], c).toBeTruthy()
    }
  })
})

describe('locale handling', () => {
  afterEach(() => setLocale('pt-BR'))

  it('detectLocale maps pt* to pt-BR and the rest to en', () => {
    const set = (l: string) =>
      Object.defineProperty(navigator, 'language', { value: l, configurable: true })
    set('pt-PT')
    expect(detectLocale()).toBe('pt-BR')
    set('pt')
    expect(detectLocale()).toBe('pt-BR')
    set('fr-FR')
    expect(detectLocale()).toBe('en')
  })

  it('setLocale updates vue-i18n and <html lang>', () => {
    setLocale('en')
    expect(i18n.global.locale.value).toBe('en')
    expect(document.documentElement.lang).toBe('en')
  })

  it('errorMessage translates the code in the active locale', () => {
    const e = new ApiError({ status: 404, code: 'NOT_FOUND', fieldErrors: [] })
    setLocale('en')
    expect(errorMessage(e)).toBe(en.errors.NOT_FOUND)
    setLocale('pt-BR')
    expect(errorMessage(e)).toBe(ptBR.errors.NOT_FOUND)
  })

  it('errorMessage falls back to errors.UNKNOWN for unknown input or codes', () => {
    setLocale('en')
    expect(errorMessage(new Error('boom'))).toBe(en.errors.UNKNOWN)
    expect(errorMessage('x')).toBe(en.errors.UNKNOWN)
    const odd = new ApiError({ status: 418, code: 'BRAND_NEW' as never, fieldErrors: [] })
    expect(errorMessage(odd)).toBe(en.errors.UNKNOWN)
  })
})
