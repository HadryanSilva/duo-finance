import { describe, expect, it } from 'vitest'
import { setLocale } from '@/shared/i18n'
import { projectionText } from './goal-copy'

const norm = (s: string | null) => s?.replace(/\s/g, ' ')

/** The copy follows the app locale; the locale argument only formats dates and money. */
function inEnglish(goal: Parameters<typeof projectionText>[0]) {
  setLocale('en')
  try {
    return norm(projectionText(goal, 'en'))
  } finally {
    setLocale('pt-BR')
  }
}

describe('projectionText', () => {
  it('goalCopy_withDateAndRequired_saysMonthlyNeeded', () => {
    const text = projectionText(
      {
        targetDate: '2027-06-01',
        projection: { requiredMonthlyCents: 7500, projectedMonth: null, targetDatePassed: false },
      },
      'pt-BR',
    )
    expect(norm(text)).toBe('Para chegar até 1 de junho de 2027 faltam € 75,00/mês')
  })

  it('goalCopy_pastDateWithPace_saysDatePassedAndPace', () => {
    const goal = {
      targetDate: '2026-12-31',
      projection: { requiredMonthlyCents: null, projectedMonth: '2027-05', targetDatePassed: true },
    }
    expect(norm(projectionText(goal, 'pt-BR'))).toBe(
      'A data-alvo (31 de dezembro de 2026) já passou. No ritmo atual chega em maio de 2027',
    )
    expect(inEnglish(goal)).toBe(
      'The target date (December 31, 2026) has passed. At the current pace it is reached in May 2027',
    )
  })

  it('goalCopy_pastDateWithoutPace_saysDatePassed', () => {
    const goal = {
      targetDate: '2026-12-31',
      projection: { requiredMonthlyCents: null, projectedMonth: null, targetDatePassed: true },
    }
    expect(norm(projectionText(goal, 'pt-BR'))).toBe(
      'A data-alvo (31 de dezembro de 2026) já passou.',
    )
    expect(inEnglish(goal)).toBe('The target date (December 31, 2026) has passed.')
  })

  it('goalCopy_withoutProjection_isNull', () => {
    expect(projectionText({ targetDate: null, projection: null }, 'pt-BR')).toBeNull()
    expect(
      projectionText(
        {
          targetDate: null,
          projection: { requiredMonthlyCents: null, projectedMonth: null, targetDatePassed: false },
        },
        'pt-BR',
      ),
    ).toBeNull()
  })
})
