import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { dirname, join } from 'node:path'
import { describe, expect, it } from 'vitest'
import {
  ARBITRARY_SIZE,
  COLOR_LITERAL,
  PALETTE_CLASS,
  guardedFiles,
  inlineStyleOffenders,
  offenders,
  unscannedFiles,
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

  it('has no Tailwind default-palette classes', () => {
    expect(offenders(PALETTE_CLASS)).toEqual([])
  })

  it('has no px/rem lengths in inline styles or style blocks', () => {
    expect(inlineStyleOffenders()).toEqual([])
  })

  it('has no source files the guard cannot scan', () => {
    expect(unscannedFiles()).toEqual([])
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

  it('rejects every CSS color function', () => {
    const fns = [
      'rgb(0 0 0)',
      'rgba(0,0,0,1)',
      'hsl(1 2% 3%)',
      'hsla(1,2%,3%,1)',
      'oklch(0.5 0.1 200)',
      'oklab(0.5 0 0)',
      'lab(50% 0 0)',
      'lch(50% 0 0)',
      'hwb(0 0% 0%)',
      'color(srgb 1 0 0)',
      'color-mix(in srgb, red, blue)',
    ]
    for (const fn of fns) {
      withFixture({ 'a.ts': `const c = '${fn}'` }, (root) => {
        expect(offenders(COLOR_LITERAL, root), fn).toHaveLength(1)
      })
    }
  })

  it('rejects arbitrary lengths and calc, but allows token references', () => {
    const bad = [
      'text-[13px]',
      'p-[7px]',
      'max-w-[calc(100%-2rem)]',
      'top-[50%]',
      'min-w-[8rem]',
      'h-[100vh]',
      'translate-x-[-50%]',
    ]
    for (const cls of bad) {
      withFixture({ 'a.vue': `<div class="${cls}">` }, (root) => {
        expect(offenders(ARBITRARY_SIZE, root), cls).toHaveLength(1)
      })
    }
    withFixture(
      { 'a.vue': '<div class="w-(--dialog-max-w) bg-[--color-brand] data-[state=open]:flex">' },
      (root) => {
        expect(offenders(ARBITRARY_SIZE, root)).toEqual([])
      },
    )
  })

  it('rejects Tailwind default-palette classes but allows token classes', () => {
    for (const cls of [
      'bg-white',
      'text-black',
      'bg-red-500',
      'border-zinc-200',
      'ring-indigo-600',
    ]) {
      withFixture({ 'a.vue': `<div class="${cls}">` }, (root) => {
        expect(offenders(PALETTE_CLASS, root), cls).toHaveLength(1)
      })
    }
    withFixture(
      { 'a.vue': '<div class="bg-brand text-foreground border-border bg-transparent">' },
      (root) => {
        expect(offenders(PALETTE_CLASS, root)).toEqual([])
      },
    )
  })

  it('rejects px/rem in inline styles and style blocks', () => {
    withFixture(
      {
        'a.vue': '<div style="margin: 4px">',
        'b.vue': '<div>x</div>\n<style>.x { padding: 1rem }</style>',
        'c.vue': '<div style="color: var(--color-brand)">',
      },
      (root) => {
        expect(inlineStyleOffenders(root).map((o) => o.split(':')[0])).toEqual(['a.vue', 'b.vue'])
      },
    )
  })

  it('scans vue, ts, tsx, js and css, and reports other extensions', () => {
    withFixture(
      { 'a.js': '', 'b.tsx': '', 'c.css': '', 'd.vue': '', 'e.ts': '', 'f.scss': '', 'g.jsx': '' },
      (root) => {
        expect(guardedFiles(root).sort()).toEqual(['a.js', 'b.tsx', 'c.css', 'd.vue', 'e.ts'])
        expect(unscannedFiles(root).sort()).toEqual(['f.scss', 'g.jsx'])
      },
    )
  })

  it('requires a real <PageHeader> usage, not just the identifier', () => {
    withFixture(
      {
        'features/a/pages/APage.vue': "import PageHeader from 'x'\n<div />",
        'features/b/pages/BPage.vue': '<PageHeader title="b" />',
      },
      (root) => {
        expect(pagesMissingHeader(root)).toEqual(['features/a/pages/APage.vue'])
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
