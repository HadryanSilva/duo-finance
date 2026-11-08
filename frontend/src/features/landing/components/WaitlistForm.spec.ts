import { screen } from '@testing-library/dom'
import { http, HttpResponse } from 'msw'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { render } from '@/test/render'
import WaitlistForm from './WaitlistForm.vue'

const api = `${window.location.origin}/api/v1`

describe('WaitlistForm', () => {
  afterEach(() => vi.restoreAllMocks())

  it('waitlist_showsSameConfirmationForAnyEmail', async () => {
    vi.spyOn(navigator, 'language', 'get').mockReturnValue('pt-BR')
    const bodies: unknown[] = []
    const r = await render(WaitlistForm, {
      handlers: [
        http.post(`${api}/public/waitlist`, async ({ request }) => {
          bodies.push(await request.json())
          return new HttpResponse(null, { status: 202 })
        }),
      ],
    })
    await r.user.type(screen.getByLabelText(/^E-mail/), '  ana@example.com ')
    await r.user.click(screen.getByRole('button', { name: 'Entrar na lista' }))
    expect((await screen.findByRole('status')).textContent).toContain('Você está na lista')
    expect(screen.queryByRole('button', { name: 'Entrar na lista' })).toBeNull()
    expect(bodies).toEqual([{ email: 'ana@example.com', locale: 'pt-BR' }])
  })

  it('waitlist_showsFieldErrorForInvalidEmail', async () => {
    const r = await render(WaitlistForm, {
      handlers: [
        http.post(`${api}/public/waitlist`, () =>
          HttpResponse.json(
            {
              code: 'VALIDATION_FAILED',
              errors: [{ field: 'email', code: 'Email' }],
            },
            { status: 400 },
          ),
        ),
      ],
    })
    await r.user.type(screen.getByLabelText(/^E-mail/), 'not-an-email')
    await r.user.click(screen.getByRole('button', { name: 'Entrar na lista' }))
    expect(await screen.findByText(/e-mail/i, { selector: 'p.text-destructive' })).toBeTruthy()
    expect(screen.queryByRole('status')).toBeNull()
  })
})
