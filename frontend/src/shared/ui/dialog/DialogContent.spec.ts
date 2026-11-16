import { screen } from '@testing-library/dom'
import { describe, expect, it } from 'vitest'
import { defineComponent, h } from 'vue'
import { render } from '@/test/render'
import { Dialog, DialogContent, DialogTitle } from './index'

const Tall = defineComponent(
  () => () =>
    h(Dialog, { open: true }, () =>
      h(DialogContent, null, () => [h(DialogTitle, null, () => 'Alto'), h('p', 'conteúdo')]),
    ),
)

describe('DialogContent', () => {
  it('scrolls its body while the close button stays outside the scroller', async () => {
    await render(Tall)
    const dialog = await screen.findByRole('dialog', { name: 'Alto' })
    expect([...dialog.classList]).toContain('max-h-dialog')
    const body = dialog.querySelector('[data-slot="dialog-body"]')
    expect(body).not.toBeNull()
    expect([...body!.classList]).toContain('overflow-y-auto')
    expect(body!.textContent).toContain('conteúdo')
    const close = screen.getByRole('button', { name: 'Fechar' })
    expect(body!.contains(close)).toBe(false)
    expect(dialog.contains(close)).toBe(true)
  })
})
