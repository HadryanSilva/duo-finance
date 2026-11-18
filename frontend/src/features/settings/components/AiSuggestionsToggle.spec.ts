import { screen, waitFor } from '@testing-library/dom'
import { http, HttpResponse, type RequestHandler } from 'msw'
import { describe, expect, it } from 'vitest'
import type { AccountResponse } from '@/shared/api/types'
import { render, testUser } from '@/test/render'
import AiSuggestionsToggle from './AiSuggestionsToggle.vue'

const api = `${window.location.origin}/api/v1`
const account = (aiSuggestionsEnabled: boolean): AccountResponse => ({
  id: testUser.accountId,
  name: 'Ana e Bia',
  currency: 'EUR',
  members: [],
  pendingInvitation: null,
  aiSuggestionsEnabled,
})

const mount = (enabled: boolean, extra: RequestHandler[] = []) =>
  render(AiSuggestionsToggle, {
    session: {},
    handlers: [...extra, http.get(`${api}/account`, () => HttpResponse.json(account(enabled)))],
  })
const toggle = () => screen.findByRole('switch', { name: 'Sugestões de categoria por IA' })

describe('AiSuggestionsToggle', () => {
  it('reflectsTheAccountSetting', async () => {
    await mount(true)
    expect((await toggle()).getAttribute('aria-checked')).toBe('true')
  })

  it('reflectsDisabledSetting', async () => {
    await mount(false)
    expect((await toggle()).getAttribute('aria-checked')).toBe('false')
  })

  it('showsTheExplanation', async () => {
    await mount(true)
    await toggle()
    expect(
      screen.getByText(
        'Ao importar um extrato, descrições de compras com cartão de comerciantes novos são enviadas à Anthropic para sugerir uma categoria. Valores, datas e nomes não são enviados.',
      ),
    ).toBeTruthy()
  })

  it('click_sendsPatchWithTheNewValue', async () => {
    let body: unknown
    const r = await mount(true, [
      http.patch(`${api}/account`, async ({ request }) => {
        body = await request.json()
        return HttpResponse.json(account(false))
      }),
    ])
    await r.user.click(await toggle())
    await waitFor(() => expect(body).toEqual({ aiSuggestionsEnabled: false }))
  })

  it('error_showsMessageAndRestoresTheValue', async () => {
    const r = await mount(true, [
      http.patch(`${api}/account`, () =>
        HttpResponse.json({ status: 500, code: 'INTERNAL_ERROR', detail: 'raw' }, { status: 500 }),
      ),
    ])
    await r.user.click(await toggle())
    expect(await screen.findByRole('alert')).toBeTruthy()
    await waitFor(async () => expect((await toggle()).getAttribute('aria-checked')).toBe('true'))
  })
})
