import { screen, waitFor } from '@testing-library/dom'
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

  it('emits close when dismissed', async () => {
    const { wrapper, user } = await render(PlainCodeDialog, {}, { props: { code: 'X-1' } })
    await user.click(await screen.findByRole('button', { name: 'Fechar' }))
    expect(wrapper.emitted('close')).toBeTruthy()
  })
})
