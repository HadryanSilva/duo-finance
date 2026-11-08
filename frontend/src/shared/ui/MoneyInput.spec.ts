import { screen } from '@testing-library/dom'
import { describe, expect, it } from 'vitest'
import { defineComponent, h, ref } from 'vue'
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

  it('moneyInput_showsExternalValueFormatted', async () => {
    const { C } = host(123456)
    await render(C)
    expect(norm((screen.getByLabelText('Saldo') as HTMLInputElement).value)).toBe('€ 1.234,56')
  })
})
