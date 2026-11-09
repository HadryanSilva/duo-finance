import { expect, test } from '@playwright/test'
import { latestLink } from './support/outbox.ts'
import { registerViaUi, uniqueEmail } from './support/users.ts'

// Spec section 6: register → onboarding → transaction → dashboard.
test('register, onboard, add expense, see it on the dashboard', async ({
  page,
  request,
  isMobile,
}) => {
  const email = uniqueEmail('ana')
  await registerViaUi(page, { email, name: 'Ana Souza', locale: 'pt-BR' })
  await expect(page).toHaveURL(/\/onboarding$/)

  await page.goto(await latestLink(request, email, '/verify-email'))
  await expect(page.getByRole('status')).toContainText('E-mail confirmado')

  await page.goto('/onboarding')
  await page.getByLabel('Nome do casal').fill('Ana e Bia')
  await page.getByRole('button', { name: 'Continuar' }).click()

  await expect(page.getByRole('heading', { name: 'Convide seu parceiro' })).toBeVisible()
  await page.getByRole('button', { name: 'Pular' }).click()

  await expect(page.getByRole('heading', { name: /primeira conta bancária/ })).toBeVisible()
  await page.getByLabel('Nome da conta').fill('Conta corrente')
  await page.getByLabel('Saldo inicial').fill('1.000,00')
  await page.getByRole('button', { name: 'Concluir' }).click()

  await expect(page).toHaveURL(/\/dashboard/)
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()

  if (isMobile) {
    await page.getByRole('link', { name: 'Novo lançamento' }).click()
  } else {
    await page.getByRole('link', { name: 'Lançamentos' }).click()
    await page.getByRole('button', { name: 'Novo lançamento' }).first().click()
  }

  const panel = page.getByRole('dialog')
  await expect(panel).toBeVisible()
  await panel.getByLabel('Valor', { exact: true }).fill('12,50')
  await panel.getByLabel('Categoria').selectOption({ label: 'Mercado' })
  await panel.getByLabel('Conta', { exact: true }).selectOption({ label: 'Conta corrente' })
  await panel.getByRole('button', { name: 'Salvar' }).click()
  await expect(panel).toBeHidden()

  await page.getByRole('link', { name: 'Dashboard' }).click()
  await expect(page).toHaveURL(/\/dashboard/)
  // pt-BR renders the euro sign followed by a non-breaking space
  const balance = /€[\s\u00a0]987,50/
  await expect(page.getByText(balance).first()).toBeVisible()
  await expect(page.getByRole('list', { name: 'Saldo por conta' })).toContainText(balance)
  await expect(page.getByRole('list', { name: 'Gastos por categoria' })).toContainText('Mercado')
})
