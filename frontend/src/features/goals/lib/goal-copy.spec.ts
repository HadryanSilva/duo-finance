import { describe, expect, it } from 'vitest'
import { projectionText } from './goal-copy'

describe('projectionText', () => {
  it('goalCopy_withDateAndRequired_saysMonthlyNeeded', () => {
    const text = projectionText(
      {
        targetDate: '2027-06-01',
        projection: { requiredMonthlyCents: 7500, projectedMonth: null },
      },
      'pt-BR',
    )
    expect(text?.replace(/\s/g, ' ')).toBe('Para chegar até 1 de junho de 2027 faltam € 75,00/mês')
  })

  it('goalCopy_withoutProjection_isNull', () => {
    expect(projectionText({ targetDate: null, projection: null }, 'pt-BR')).toBeNull()
    expect(
      projectionText(
        { targetDate: null, projection: { requiredMonthlyCents: null, projectedMonth: null } },
        'pt-BR',
      ),
    ).toBeNull()
  })
})
