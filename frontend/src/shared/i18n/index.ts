import { createI18n } from 'vue-i18n'
import type { Locale } from '../lib/money'
import en from './locales/en.json'
import ptBR from './locales/pt-BR.json'

export type { Locale }

export const i18n = createI18n({
  legacy: false,
  locale: 'pt-BR',
  fallbackLocale: 'en',
  messages: { 'pt-BR': ptBR, en },
})

export function setLocale(locale: Locale): void {
  i18n.global.locale.value = locale
  document.documentElement.lang = locale
}

export function detectLocale(): Locale {
  return navigator.language.toLowerCase().startsWith('pt') ? 'pt-BR' : 'en'
}
