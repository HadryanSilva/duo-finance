import { expect, test, type Page } from '@playwright/test'
import { latestLink } from './support/outbox.ts'
import { revolutSample } from './support/revolut-csv.ts'
import { registerViaUi, uniqueEmail } from './support/users.ts'

async function uploadAndReview(page: Page, csv: string) {
  await page.goto('/transactions/import')
  await expect(page.getByRole('heading', { name: 'Importar extrato da Revolut' })).toBeVisible()
  await page.getByLabel('Conta de destino').selectOption({ label: 'Conta corrente' })
  await page.getByLabel('Arquivo CSV').setInputFiles({
    name: 'revolut.csv',
    mimeType: 'text/csv',
    buffer: Buffer.from(csv),
  })
  await page.getByRole('button', { name: 'Revisar' }).click()
}

// Revolut CSV: upload → review → import → re-import is a no-op → undo from the history.
test('import, reimport, undo', async ({ page, request }) => {
  const email = uniqueEmail('ivo')
  const sample = revolutSample(String(Date.now()))
  await registerViaUi(page, { email, name: 'Ivo Reis', locale: 'pt-BR' })
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.goto(await latestLink(request, email, '/verify-email'))
  await expect(page.getByRole('status')).toContainText('E-mail confirmado')

  await page.goto('/onboarding')
  await page.getByLabel('Nome do casal').fill('Ivo e Eva')
  await page.getByRole('button', { name: 'Continuar' }).click()
  await page.getByRole('button', { name: 'Pular' }).click()
  await page.getByLabel('Nome da conta').fill('Conta corrente')
  await page.getByLabel('Saldo inicial').fill('0,00')
  await page.getByRole('button', { name: 'Concluir' }).click()
  await expect(page).toHaveURL(/\/dashboard/)

  // First import: 3 rows to create, the PENDING one is skipped.
  await uploadAndReview(page, sample.csv)
  await expect(page.getByRole('tab', { name: 'Novas (3)' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Puladas (1)' })).toBeVisible()
  await page.getByRole('tab', { name: 'Puladas (1)' }).click()
  await expect(page.getByText(sample.pending)).toBeVisible()
  await page.getByRole('tab', { name: 'Novas (3)' }).click()
  const submit = page.getByRole('button', { name: 'Importar 3 lançamentos' })
  await expect(submit).toBeEnabled()
  await submit.click()
  await expect(page.getByRole('heading', { name: 'Importação concluída' })).toBeVisible()
  await expect(page.getByText('3 lançamentos importados, 0 já existiam')).toBeVisible()

  await page.getByRole('link', { name: 'Ver lançamentos' }).click()
  await expect(page).toHaveURL(/\/transactions$/)
  for (const description of sample.completed) {
    await expect(page.getByText(description).first()).toBeVisible()
  }
  await expect(page.getByText(sample.pending)).toHaveCount(0)

  // Same file again: every row is already imported, so nothing is left to include.
  await uploadAndReview(page, sample.csv)
  await expect(page.getByText('Já importada')).toHaveCount(3)
  await expect(page.getByText('0 linhas incluídas')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Importar 0 lançamentos' })).toBeDisabled()

  // Undo the batch from the history.
  await page.getByRole('button', { name: /^Desfazer importação de / }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog.getByText('Desfazer esta importação?')).toBeVisible()
  await dialog.getByRole('button', { name: 'Desfazer', exact: true }).click()
  await expect(page.getByText('Desfeita')).toBeVisible()

  await page.goto('/transactions')
  await expect(page.getByRole('heading', { name: 'Lançamentos' }).first()).toBeVisible()
  for (const description of sample.completed) {
    await expect(page.getByText(description)).toHaveCount(0)
  }
})
