import { expect, test, type Page } from '@playwright/test'
import { latestLink } from './support/outbox.ts'
import { registerViaUi, uniqueEmail } from './support/users.ts'

async function openNewTransaction(page: Page) {
  await page.goto('/transactions?new=1')
  const panel = page.getByRole('dialog')
  await expect(panel).toBeVisible()
  return panel
}

// Invitation → acceptance: both partners share one Account.
test('invite partner and partner accepts; both see the same transaction', async ({
  browser,
  request,
}) => {
  const anaEmail = uniqueEmail('ana')
  const biaEmail = uniqueEmail('bia')
  const description = `Feira ${Date.now()}`

  const anaContext = await browser.newContext()
  const bia = await browser.newContext()
  try {
    // Ana registers, verifies her e-mail (required to invite) and invites Bia.
    const ana = await anaContext.newPage()
    await registerViaUi(ana, { email: anaEmail, name: 'Ana Souza', locale: 'pt-BR' })
    await expect(ana).toHaveURL(/\/onboarding$/)
    await ana.goto(await latestLink(request, anaEmail, '/verify-email'))
    await expect(ana.getByRole('status')).toContainText('E-mail confirmado')

    await ana.goto('/onboarding')
    await ana.getByLabel('Nome do casal').fill('Ana e Bia')
    await ana.getByRole('button', { name: 'Continuar' }).click()
    await ana.getByLabel('E-mail do parceiro').fill(biaEmail)
    await ana.getByRole('button', { name: 'Enviar convite' }).click()
    await expect(ana.getByRole('status')).toContainText(`Convite enviado para ${biaEmail}`)
    await ana.getByRole('button', { name: 'Continuar' }).click()
    await ana.getByRole('button', { name: 'Pular' }).click()
    await expect(ana).toHaveURL(/\/dashboard/)

    // Bia opens the invitation and creates her account through it (no access code).
    const biaPage = await bia.newPage()
    await biaPage.goto(await latestLink(request, biaEmail, '/invite/'))
    await expect(biaPage.getByText('Ana e Bia')).toBeVisible()
    await biaPage.getByRole('link', { name: 'Criar conta' }).click()
    await expect(biaPage).toHaveURL(/\/register\?/)
    await expect(biaPage.getByLabel('Código de acesso')).toHaveCount(0)
    await registerViaUi(biaPage, { email: biaEmail, name: 'Bia Lima', locale: 'pt-BR' })
    await expect(biaPage).toHaveURL(/\/dashboard/)
    await expect(biaPage.getByRole('heading', { name: 'Dashboard' })).toBeVisible()

    // Ana records an expense; Bia sees it with Ana as the author.
    const panel = await openNewTransaction(ana)
    await panel.getByLabel('Valor', { exact: true }).fill('30,00')
    await panel.getByLabel('Descrição').fill(description)
    await panel.getByLabel('Categoria').selectOption({ label: 'Mercado' })
    await panel.getByRole('button', { name: 'Salvar' }).click()
    await expect(panel).toBeHidden()

    await biaPage.goto('/transactions')
    const row = biaPage
      .getByRole('list', { name: 'Lançamentos por dia' })
      .getByRole('button', { name: description })
    await expect(row).toBeVisible()
    await expect(row.getByRole('img', { name: 'Ana Souza' })).toHaveText('AS')
  } finally {
    await anaContext.close()
    await bia.close()
  }
})
