import { screen } from '@testing-library/dom'
import { describe, expect, it } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { render } from '@/test/render'
import Checkbox from './Checkbox.vue'

function host(initial: boolean, attrs: Record<string, unknown> = {}) {
  const value = ref(initial)
  const updates: boolean[] = []
  const C = defineComponent({
    setup: () => () =>
      h('div', [
        h(Checkbox, {
          id: 'c',
          modelValue: value.value,
          'onUpdate:modelValue': (v: boolean | undefined) => {
            updates.push(!!v)
            value.value = !!v
          },
          ...attrs,
        }),
        h('label', { for: 'c' }, 'Accept'),
      ]),
  })
  return { C, value, updates }
}

const box = () => screen.getByLabelText('Accept') as HTMLInputElement

describe('Checkbox', () => {
  it('toggles the model', async () => {
    const { C, updates } = host(false)
    const r = await render(C)
    await r.user.click(box())
    expect(updates).toEqual([true])
  })

  it('reflects the model', async () => {
    const { C, value } = host(true)
    await render(C)
    expect(screen.getByRole('checkbox')).toBe(box())
    expect(box().checked).toBe(true)
    value.value = false
    await nextTick()
    expect(box().checked).toBe(false)
  })

  it('passes attrs, not class, to the input', async () => {
    await render(host(false, { disabled: true, 'aria-invalid': 'true', class: 'mt-0.5' }).C)
    expect(box().disabled).toBe(true)
    expect(box().getAttribute('aria-invalid')).toBe('true')
    expect(box().classList.contains('appearance-none')).toBe(true)
    expect(box().classList.contains('mt-0.5')).toBe(false)
    expect(box().parentElement!.classList.contains('mt-0.5')).toBe(true)
  })

  it('disabled does not toggle', async () => {
    const { C, updates } = host(false, { disabled: true })
    const r = await render(C)
    await r.user.click(box())
    expect(updates).toEqual([])
  })
})
