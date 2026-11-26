import { screen } from '@testing-library/dom'
import { describe, expect, it, vi } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { render } from '@/test/render'
import NativeSelect from './NativeSelect.vue'

function host(initial: string, attrs: Record<string, unknown> = {}) {
  const value = ref(initial)
  const updates: string[] = []
  const C = defineComponent({
    setup: () => () =>
      h('div', [
        h('label', { for: 's' }, 'Field'),
        h(
          NativeSelect,
          {
            id: 's',
            modelValue: value.value,
            'onUpdate:modelValue': (v: string | undefined) => {
              updates.push(v ?? '')
              value.value = v ?? ''
            },
            ...attrs,
          },
          () => [h('option', { value: 'a' }, 'A'), h('option', { value: 'b' }, 'B')],
        ),
      ]),
  })
  return { C, value, updates }
}

const select = () => screen.getByLabelText('Field') as HTMLSelectElement

describe('NativeSelect', () => {
  it('renders a decorative chevron', async () => {
    await render(host('a').C)
    expect(select().parentElement!.querySelector('svg[aria-hidden="true"]')).not.toBeNull()
  })

  it('v-model updates on choice', async () => {
    const { C, updates } = host('a')
    const r = await render(C)
    await r.user.selectOptions(select(), 'b')
    expect(updates).toEqual(['b'])
  })

  it('follows an external model change', async () => {
    const { C, value } = host('a')
    await render(C)
    expect(select().value).toBe('a')
    value.value = 'b'
    await nextTick()
    expect(select().value).toBe('b')
  })

  it('passes attrs to the select and class to the wrapper', async () => {
    await render(host('a', { disabled: true, 'aria-invalid': 'true', class: 'w-40' }).C)
    expect(select().disabled).toBe(true)
    expect(select().getAttribute('aria-invalid')).toBe('true')
    expect(select().classList.contains('appearance-none')).toBe(true)
    expect(select().classList.contains('w-40')).toBe(false)
    expect(select().parentElement!.classList.contains('w-40')).toBe(true)
  })

  it('forwards a raw change listener', async () => {
    const onChange = vi.fn()
    const r = await render(host('a', { onChange }).C)
    await r.user.selectOptions(select(), 'b')
    expect(onChange).toHaveBeenCalledTimes(1)
    expect((onChange.mock.calls[0]![0] as Event).target).toBe(select())
  })
})
