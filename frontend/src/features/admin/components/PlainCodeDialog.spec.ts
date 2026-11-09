import { fireEvent, screen, waitFor } from '@testing-library/dom'
import { describe, expect, it, vi } from 'vitest'
import { render } from '@/test/render'
import PlainCodeDialog from './PlainCodeDialog.vue'

describe('PlainCodeDialog', () => {
  it('shows the code with the one-time warning', async () => {
    await render(PlainCodeDialog, {}, { props: { code: 'DUO-ABCD-1234' } })
    expect(await screen.findByText('DUO-ABCD-1234')).toBeTruthy()
    expect(screen.getByText(/não será mostrado de novo/i)).toBeTruthy()
  })

  it('copies through the clipboard', async () => {
    const { user } = await render(PlainCodeDialog, {}, { props: { code: 'DUO-ABCD-1234' } })
    const writeText = vi.spyOn(navigator.clipboard, 'writeText').mockResolvedValue()
    await user.click(await screen.findByRole('button', { name: 'Copiar' }))
    expect(writeText).toHaveBeenCalledWith('DUO-ABCD-1234')
    expect(await screen.findByText('Copiado')).toBeTruthy()
  })

  it('selects the text and hints when the clipboard fails', async () => {
    const { user } = await render(PlainCodeDialog, {}, { props: { code: 'DUO-ABCD-1234' } })
    vi.spyOn(navigator.clipboard, 'writeText').mockRejectedValue(new Error('denied'))
    await user.click(await screen.findByRole('button', { name: 'Copiar' }))
    await waitFor(() => expect(screen.getByText(/copie manualmente/i)).toBeTruthy())
    expect(window.getSelection()?.toString()).toBe('DUO-ABCD-1234')
  })

  it('is not dismissed by a click outside', async () => {
    const { wrapper } = await render(PlainCodeDialog, {}, { props: { code: 'X-1' } })
    await screen.findByText('X-1')
    const outside = document.querySelector('[data-slot="dialog-overlay"]') ?? document.body
    await new Promise((r) => setTimeout(r, 20))
    await fireEvent.pointerDown(document.body)
    await fireEvent.pointerDown(outside)
    await fireEvent.pointerUp(outside)
    await fireEvent.click(outside)
    expect(wrapper.emitted('close')).toBeUndefined()
    expect(screen.getByText('X-1')).toBeTruthy()
  })

  it('closes with Escape', async () => {
    const { wrapper, user } = await render(PlainCodeDialog, {}, { props: { code: 'X-1' } })
    await screen.findByText('X-1')
    await user.keyboard('{Escape}')
    expect(wrapper.emitted('close')).toBeTruthy()
  })

  it('emits close when dismissed', async () => {
    const { wrapper, user } = await render(PlainCodeDialog, {}, { props: { code: 'X-1' } })
    await user.click(await screen.findByRole('button', { name: 'Fechar' }))
    expect(wrapper.emitted('close')).toBeTruthy()
  })
})
