import { screen } from '@testing-library/dom'
import { describe, expect, it } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { render } from '@/test/render'
import MoneyInput from './MoneyInput.vue'

const norm = (s: string) => s.replace(/\s/g, ' ')

function host(initial: number | null, allowNegative = false) {
  const value = ref<number | null>(initial)
  const C = defineComponent({
    setup: () => () =>
      h(MoneyInput, {
        label: 'Saldo',
        allowNegative,
        modelValue: value.value,
        'onUpdate:modelValue': (v: number | null) => (value.value = v),
      }),
  })
  return { C, value }
}

describe('MoneyInput', () => {
  it('moneyInput_clear_emptiesFieldAndEmitsNull', async () => {
    const { C, value } = host(0)
    const r = await render(C)
    const input = screen.getByLabelText('Saldo') as HTMLInputElement
    await r.user.click(input) // focus first, as a real user does, so the focus re-render settles
    await r.user.clear(input)
    expect(input.value).toBe('')
    expect(value.value).toBeNull()
  })

  it('moneyInput_emitsCentsAndFormatsOnBlur', async () => {
    const { C, value } = host(null)
    const r = await render(C)
    const input = screen.getByLabelText('Saldo') as HTMLInputElement
    expect(input.getAttribute('inputmode')).toBe('decimal')
    await r.user.click(input)
    await r.user.type(input, '1.250,5')
    expect(value.value).toBe(125050)
    expect(input.value).toBe('1.250,5')
    await r.user.tab()
    expect(norm(input.value)).toBe('€ 1.250,50')
    expect(value.value).toBe(125050)
  })

  it('moneyInput_invalidText_emitsNull', async () => {
    const { C, value } = host(100)
    const r = await render(C)
    const input = screen.getByLabelText('Saldo') as HTMLInputElement
    await r.user.clear(input)
    await r.user.type(input, '1,234')
    expect(value.value).toBeNull()
    expect(input.getAttribute('aria-invalid')).toBe('true')
  })

  it('moneyInput_negativeRejectedUnlessAllowed', async () => {
    const a = host(null, false)
    const r = await render(a.C)
    await r.user.type(screen.getByLabelText('Saldo'), '-5,00')
    expect(a.value.value).toBeNull()
  })

  it('moneyInput_allowNegative_acceptsAndFormatsNegatives', async () => {
    const b = host(null, true)
    const r2 = await render(b.C)
    const input = screen.getByLabelText('Saldo') as HTMLInputElement
    await r2.user.type(input, '-5,00')
    expect(b.value.value).toBe(-500)
    await r2.user.tab()
    expect(norm(input.value)).toBe('-€ 5,00')
    await r2.user.click(input)
    expect(input.value).toBe('-5,00')
  })

  it('moneyInput_selectAllOnFocus_survivesTheRawTextSwap', async () => {
    // Tab focus (and Playwright's fill) selects the whole text; typing must replace "0,00".
    const { C } = host(0)
    await render(C)
    const input = screen.getByLabelText('Saldo') as HTMLInputElement
    input.focus()
    input.select()
    await nextTick()
    expect(input.value).toBe('0,00')
    expect([input.selectionStart, input.selectionEnd]).toEqual([0, input.value.length])
  })

  it('moneyInput_selectAfterFocusThenType_replacesTheAmount', async () => {
    // Chrome ordering: focus lands first, the selection is made afterwards, then the user types.
    const { C, value } = host(0)
    const r = await render(C)
    const input = screen.getByLabelText('Saldo') as HTMLInputElement
    input.focus()
    await nextTick()
    input.select()
    await r.user.keyboard('5')
    expect(input.value).toBe('5')
    expect(value.value).toBe(500)
  })

  it('moneyInput_selectAllOnFocusThenType_replacesTheAmount', async () => {
    // Playwright's fill: select() and focus() in one task, typing after the microtasks ran.
    const { C, value } = host(0)
    const r = await render(C)
    const input = screen.getByLabelText('Saldo') as HTMLInputElement
    input.select()
    input.focus()
    await nextTick()
    await r.user.keyboard('5')
    expect(input.value).toBe('5')
    expect(value.value).toBe(500)
  })

  it('moneyInput_tabFocusWithFullSelection_keepsItSelected', async () => {
    const { C } = host(123456)
    await render(C)
    const input = screen.getByLabelText('Saldo') as HTMLInputElement
    input.setSelectionRange(0, input.value.length)
    input.focus()
    await nextTick()
    expect(input.value).toBe('1234,56')
    expect([input.selectionStart, input.selectionEnd]).toEqual([0, input.value.length])
  })

  it('moneyInput_showsExternalValueFormatted', async () => {
    const { C } = host(123456)
    await render(C)
    expect(norm((screen.getByLabelText('Saldo') as HTMLInputElement).value)).toBe('€ 1.234,56')
  })
})
