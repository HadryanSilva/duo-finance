import { screen } from '@testing-library/dom'
import { describe, expect, it, vi } from 'vitest'
import { render } from '@/test/render'
import MonthSelector from './MonthSelector.vue'

async function mountSelector(
  modelValue: string,
  locale: 'pt-BR' | 'en' = 'pt-BR',
  limits: { min?: string; max?: string } = {},
) {
  const onUpdate = vi.fn()
  const done = await render(
    MonthSelector,
    { locale },
    { props: { modelValue, 'onUpdate:modelValue': onUpdate, ...limits } },
  )
  return { onUpdate, ...done }
}

const previous = () => screen.getByRole<HTMLButtonElement>('button', { name: 'Mês anterior' })
const next = () => screen.getByRole<HTMLButtonElement>('button', { name: 'Próximo mês' })

describe('MonthSelector limits', () => {
  it('monthSelector_atMax_disablesNext', async () => {
    await mountSelector('2027-02', 'pt-BR', { max: '2027-02' })
    expect(next().disabled).toBe(true)
    expect(previous().disabled).toBe(false)
  })

  it('monthSelector_atMin_disablesPrevious', async () => {
    await mountSelector('2026-11', 'pt-BR', { min: '2026-11' })
    expect(previous().disabled).toBe(true)
    expect(next().disabled).toBe(false)
  })

  it('monthSelector_withoutLimits_bothEnabled', async () => {
    await mountSelector('2026-11')
    expect(previous().disabled).toBe(false)
    expect(next().disabled).toBe(false)
  })
})

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
