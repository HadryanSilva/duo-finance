import { screen } from '@testing-library/dom'
import { describe, expect, it, vi } from 'vitest'
import { render } from '@/test/render'
import MonthSelector from './MonthSelector.vue'

async function mountSelector(modelValue: string, locale: 'pt-BR' | 'en' = 'pt-BR') {
  const onUpdate = vi.fn()
  const done = await render(
    MonthSelector,
    { locale },
    { props: { modelValue, 'onUpdate:modelValue': onUpdate } },
  )
  return { onUpdate, ...done }
}

describe('MonthSelector', () => {
  it('buttons_emitPreviousAndNextMonth', async () => {
    const { onUpdate, user } = await mountSelector('2026-01')
    await user.click(screen.getByRole('button', { name: 'Mês anterior' }))
    expect(onUpdate).toHaveBeenLastCalledWith('2025-12')
    await user.click(screen.getByRole('button', { name: 'Próximo mês' }))
    expect(onUpdate).toHaveBeenLastCalledWith('2026-02')
  })

  it('label_isCapitalisedOnlyAtTheFirstLetterInPtBr', async () => {
    await mountSelector('2026-03')
    // the month name is capitalised; the "de" connector stays lowercase
    expect(screen.getByText('Março de 2026')).toBeTruthy()
  })

  it('label_isCapitalisedInEnglish', async () => {
    await mountSelector('2026-03', 'en')
    expect(screen.getByText('March 2026')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Previous month' })).toBeTruthy()
    expect(screen.getByRole('button', { name: 'Next month' })).toBeTruthy()
  })

  it('group_hasAnAccessibleName', async () => {
    await mountSelector('2026-03')
    expect(screen.getByRole('group', { name: 'Mês' })).toBeTruthy()
  })
})
