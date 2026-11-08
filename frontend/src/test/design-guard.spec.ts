import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  ARBITRARY_SIZE,
  COLOR_LITERAL,
  guardedFiles,
  offenders,
  pagesMissingHeader,
  pagesRequiringHeader,
} from './design-guard'

describe('design guard', () => {
  it('has no hex or rgb/hsl colors outside the token file', () => {
    expect(offenders(COLOR_LITERAL)).toEqual([])
  })

  it('has no arbitrary size values in classes', () => {
    expect(offenders(ARBITRARY_SIZE)).toEqual([])
  })

  it('every page under features/**/pages except landing, auth and onboarding uses PageHeader', () => {
    expect(pagesMissingHeader()).toEqual([])
  })

  it('actually scans the source tree', () => {
    const files = guardedFiles()
    expect(files).toContain('shared/ui/Logo.vue')
    expect(files).not.toContain('app/styles.css')
  })
})

describe('design guard checkers', () => {
  function withFixture(files: Record<string, string>, run: (root: string) => void) {
    const root = mkdtempSync(join(tmpdir(), 'guard-'))
    try {
      for (const [path, content] of Object.entries(files)) {
        mkdirSync(dirname(join(root, path)), { recursive: true })
        writeFileSync(join(root, path), content)
      }
      run(root)
    } finally {
      rmSync(root, { recursive: true, force: true })
    }
  }

  it('flags color literals and arbitrary sizes, ignoring styles.css, schema.ts and specs', () => {
    withFixture(
      {
        'a.vue': '<div class="text-[13px] bg-red-500" style="color: #fff">',
        'b.vue': '<div style="color: rgb(0 0 0)">',
        'app/styles.css': '--c: #fff;',
        'shared/api/schema.ts': "const x = '#fff'",
        'c.spec.ts': "const x = '#fff'",
      },
      (root) => {
        expect(offenders(COLOR_LITERAL, root).map((o) => o.split(':')[0])).toEqual([
          'a.vue',
          'b.vue',
        ])
        expect(offenders(ARBITRARY_SIZE, root).map((o) => o.split(':')[0])).toEqual(['a.vue'])
      },
    )
  })

  it('requires PageHeader on pages but exempts landing, auth and onboarding', () => {
    withFixture(
      {
        'features/budgets/pages/BudgetsPage.vue': '<div />',
        'features/transactions/pages/TransactionsPage.vue': '<PageHeader title="x" />',
        'features/landing/pages/LandingPage.vue': '<div />',
        'features/auth/pages/LoginPage.vue': '<div />',
        'features/onboarding/pages/WelcomePage.vue': '<div />',
        'features/budgets/components/Card.vue': '<div />',
      },
      (root) => {
        expect(pagesRequiringHeader(guardedFiles(root)).sort()).toEqual([
          'features/budgets/pages/BudgetsPage.vue',
          'features/transactions/pages/TransactionsPage.vue',
        ])
        expect(pagesMissingHeader(root)).toEqual(['features/budgets/pages/BudgetsPage.vue'])
      },
    )
  })
})
