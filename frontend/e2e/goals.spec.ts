import { expect, test, type Page } from '@playwright/test'
import { latestLink } from './support/outbox.ts'
import { registerViaUi, uniqueEmail } from './support/users.ts'

// Money has a non-breaking space after the euro sign, so '.' stands for it.
const euro = (amount: string) => new RegExp('€.' + amount.replace('.', '[.]'))

/** Runs a goal row action: an icon button on desktop, an item of the "⋯" menu on mobile. */
async function rowAction(page: Page, goal: string, menuLabel: string, desktopLabel: string) {
  if (test.info().project.name === 'mobile') {
    await page.getByRole('button', { name: `Ações para ${goal}` }).click()
    await page.getByRole('menuitem', { name: menuLabel, exact: true }).click()
  } else {
    await page.getByRole('button', { name: desktopLabel, exact: true }).click()
  }
}

// Goals: salary income → monthly suggestion → confirm → withdraw → conclude.
test('goals: suggest, confirm, withdraw, conclude', async ({ page, request }) => {
  test.setTimeout(90_000) // long flow; the full suite runs workers in parallel
  const email = uniqueEmail('gal')
  await registerViaUi(page, { email, name: 'Gal Dias', locale: 'pt-BR' })
  await expect(page).toHaveURL(/\/onboarding$/)
  await page.goto(await latestLink(request, email, '/verify-email'))
  await expect(page.getByRole('status')).toContainText('E-mail confirmado')

  await page.goto('/onboarding')
  await page.getByLabel('Nome do casal').fill('Gal e Gil')
  await page.getByRole('button', { name: 'Continuar' }).click()
  await page.getByRole('button', { name: 'Pular', exact: true }).click()
  await page.getByLabel('Nome da conta').fill('Conta corrente')
  await page.getByLabel('Saldo inicial').fill('0,00')
  await page.getByRole('button', { name: 'Concluir', exact: true }).click()
  await expect(page).toHaveURL(/\/dashboard/)

  // Salary income of € 2.000,00 today, in the seeded "Salário" category.
  await page.goto('/transactions?new=1')
  const panel = page.getByRole('dialog')
  await panel.getByText('Receita', { exact: true }).click()
  await panel.getByLabel('Valor').fill('2.000,00')
  await panel.getByLabel('Descrição').fill('Salário do mês')
  await panel.getByLabel('Categoria').selectOption({ label: 'Salário' })
  await panel.getByRole('button', { name: 'Salvar', exact: true }).click()
  await expect(page.getByText('Salário do mês').first()).toBeVisible()

  // Salary category + goal "Reserva" at 10%.
  await page.goto('/goals')
  await page.getByLabel('O salário vem da categoria').selectOption({ label: 'Salário' })
  await expect(page.getByLabel('O salário vem da categoria')).toHaveValue(/.+/)
  await page.getByRole('button', { name: 'Novo objetivo' }).first().click()
  const form = page.getByRole('dialog')
  await form.getByLabel('Nome', { exact: true }).fill('Reserva')
  await form.getByLabel('Valor alvo').fill('5.000,00')
  await form.getByLabel('Porcentagem do salário').fill('10')
  await form.getByRole('button', { name: 'Salvar', exact: true }).click()
  await expect(
    page.getByRole('list', { name: 'Objetivos', exact: true }).getByText('Reserva'),
  ).toBeVisible()

  // Dashboard suggests 10% of the salary.
  await page.goto('/dashboard')
  const suggestions = page.getByRole('list', { name: 'Sugestões do mês', exact: true })
  await expect(suggestions.getByText(euro('200,00'))).toBeVisible()
  await page.getByRole('button', { name: 'Confirmar Reserva' }).click()

  const goalsList = page.getByRole('list', { name: 'Andamento dos objetivos', exact: true })
  await expect(goalsList).toContainText(/€\s200,00\s*\/\s*€\s5\.000,00/)
  await expect(page.getByText('Saldo livre')).toBeVisible()

  // Withdraw € 50,00 → € 150,00 saved.
  await page.goto('/goals')
  await rowAction(page, 'Reserva', 'Resgatar', 'Resgatar de Reserva')
  const amount = page.getByRole('dialog')
  await amount.getByLabel('Valor').fill('50,00')
  await amount.getByRole('button', { name: 'Resgatar', exact: true }).click()
  const row = page.getByRole('list', { name: 'Objetivos', exact: true })
  await expect(row).toContainText(/€\s150,00\s*\/\s*€\s5\.000,00/)

  // Conclude → moves under "Concluídos".
  await rowAction(page, 'Reserva', 'Concluir', 'Concluir Reserva')
  await page.getByRole('dialog').getByRole('button', { name: 'Concluir', exact: true }).click()
  const done = page.getByRole('list', { name: 'Concluídos', exact: true })
  await expect(done.getByText('Reserva')).toBeVisible()
})
