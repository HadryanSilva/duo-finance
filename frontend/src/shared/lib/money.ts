export type Locale = 'pt-BR' | 'en'

const SEPARATORS: Record<Locale, { decimal: string; thousands: string }> = {
  'pt-BR': { decimal: ',', thousands: '.' },
  en: { decimal: '.', thousands: ',' },
}

const formatters = new Map<Locale, Intl.NumberFormat>()

export function formatMoney(cents: number, locale: Locale): string {
  let nf = formatters.get(locale)
  if (!nf) {
    nf = new Intl.NumberFormat(locale, { style: 'currency', currency: 'EUR' })
    formatters.set(locale, nf)
  }
  return nf.format(cents / 100)
}

const escape = (c: string) => `[${c}]`

/**
 * Parses a typed amount into integer cents, working on the string (no parseFloat).
 * Thousands separators are optional but must group by 3; at most 2 decimals; no decimal
 * separator means whole units. A surrounding `€` and whitespace are tolerated.
 * Returns null for anything else.
 */
export function parseMoney(
  input: string,
  locale: Locale,
  { allowNegative = false }: { allowNegative?: boolean } = {},
): number | null {
  const { decimal, thousands } = SEPARATORS[locale]
  const text = input.replace(/^\s*€?\s*/, '').replace(/\s*€?\s*$/, '')
  const sign = allowNegative ? '(-)?' : ''
  const re = new RegExp(
    String.raw`^${sign}(\d{1,3}(?:${escape(thousands)}\d{3})+|\d+)?(?:${escape(decimal)}(\d{1,2}))?$`,
  )
  const m = re.exec(text)
  if (!m) return null
  const [, neg, intPart, frac] = allowNegative ? m : [m[0], undefined, m[1], m[2]]
  if (intPart === undefined && frac === undefined) return null
  const whole = Number((intPart ?? '0').split(thousands).join(''))
  const cents = whole * 100 + Number((frac ?? '').padEnd(2, '0'))
  if (!Number.isSafeInteger(cents)) return null
  return neg && cents !== 0 ? -cents : cents
}
