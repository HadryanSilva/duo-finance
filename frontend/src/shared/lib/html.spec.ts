import { describe, expect, it } from 'vitest'
import { escapeHtml } from './html'

describe('escapeHtml', () => {
  it('escapes every HTML-significant character', () => {
    expect(escapeHtml(`<a href="x" title='y'>&</a>`)).toBe(
      '&lt;a href=&quot;x&quot; title=&#39;y&#39;&gt;&amp;&lt;/a&gt;',
    )
  })

  it('leaves plain text alone', () => {
    expect(escapeHtml('Mercado & Feira: € 12,50')).toBe('Mercado &amp; Feira: € 12,50')
    expect(escapeHtml('Lazer')).toBe('Lazer')
  })
})
