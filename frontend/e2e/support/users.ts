import { expect, type Page } from '@playwright/test'

export const PASSWORD = 'e2e-Password-123'

/** A fresh address per call, so tests never collide and need no database cleanup. */
export function uniqueEmail(prefix: string): string {
  const random = Math.random().toString(36).slice(2, 8)
  return `${prefix}+${Date.now()}${random}@e2e.test`
}

type NewUser = { email: string; name: string; locale: 'pt-BR' | 'en' }

/**
 * Fills the real register page (OPEN mode or an invitation, so no access code) and submits it.
 * Opens `/register` unless the page is already there (e.g. coming from an invitation).
 */
export async function registerViaUi(page: Page, { email, name, locale }: NewUser): Promise<void> {
  if (!new URL(page.url()).pathname.startsWith('/register')) await page.goto('/register')
  const emailField = page.getByLabel('E-mail', { exact: true })
  await emailField.fill(email)
  await page.getByLabel('Senha', { exact: true }).fill(PASSWORD)
  await page.getByLabel('Nome', { exact: true }).fill(name)
  await page.getByLabel('Idioma', { exact: true }).selectOption(locale)
  await page.getByRole('checkbox').check()
  const submit = page.getByRole('button', { name: 'Criar conta' })
  await expect(submit).toBeEnabled()
  await submit.click()
}
