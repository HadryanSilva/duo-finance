import { screen } from '@testing-library/dom'
import { describe, expect, it } from 'vitest'
import { defineComponent, h, nextTick, ref } from 'vue'
import { render } from '@/test/render'
import FileInput from './FileInput.vue'

function host(disabled = false) {
  const value = ref<File | null>(null)
  const updates: (File | null)[] = []
  const C = defineComponent({
    setup: () => () =>
      h(FileInput, {
        label: 'Arquivo CSV',
        accept: '.csv',
        disabled,
        modelValue: value.value,
        'onUpdate:modelValue': (v: File | null) => {
          updates.push(v)
          value.value = v
        },
      }),
  })
  return { C, value, updates }
}

const input = () => screen.getByLabelText('Arquivo CSV') as HTMLInputElement
const csv = () => new File(['x'], 'extrato.csv', { type: 'text/csv' })

describe('FileInput', () => {
  it('shows the empty text', async () => {
    await render(host().C)
    expect(screen.getByText('Nenhum arquivo selecionado')).toBeTruthy()
    expect(screen.getByText('Escolher arquivo')).toBeTruthy()
  })

  it('shows the chosen file name', async () => {
    const { C, updates } = host()
    const r = await render(C)
    await r.user.upload(input(), csv())
    expect(updates.map((f) => f?.name)).toEqual(['extrato.csv'])
    expect(screen.getByText('extrato.csv')).toBeTruthy()
  })

  it('names the file in full on hover, since a long name is clamped', async () => {
    const { C } = host()
    const r = await render(C)
    await r.user.upload(input(), csv())
    expect(screen.getByText('extrato.csv').getAttribute('title')).toBe('extrato.csv')
  })

  it('clears when the model resets', async () => {
    const { C, value } = host()
    const r = await render(C)
    await r.user.upload(input(), csv())
    expect(input().files).toHaveLength(1)
    value.value = null
    await nextTick()
    expect(input().files).toHaveLength(0)
    expect(screen.getByText('Nenhum arquivo selecionado')).toBeTruthy()
  })

  it('is reachable by keyboard', async () => {
    const r = await render(host().C)
    await r.user.tab()
    expect(document.activeElement).toBe(input())
  })

  it('disabled blocks uploads', async () => {
    const { C, updates } = host(true)
    const r = await render(C)
    expect(input().disabled).toBe(true)
    await r.user.upload(input(), csv())
    expect(updates).toEqual([])
  })
})
