import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

export const SRC = join(dirname(fileURLToPath(import.meta.url)), '..')

const EXEMPT = new Set(['app/styles.css', 'shared/api/schema.ts'])
export const SCANNED_EXT = /\.(vue|ts|tsx|js|css|json)$/

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name)
    return statSync(full).isDirectory() ? walk(full) : [full]
  })
}

function posix(path: string): string {
  return path.split(sep).join('/')
}

function isSpec(file: string): boolean {
  return /\.spec\.(ts|tsx|js)$/.test(file)
}

/** Every source file under root that the guard scans (relative posix paths). */
export function guardedFiles(root: string = SRC): string[] {
  return walk(root)
    .map((f) => posix(relative(root, f)))
    .filter((f) => SCANNED_EXT.test(f) && !isSpec(f) && !EXEMPT.has(f))
}

/** Files under root with an extension the guard cannot scan (so they could hide a violation). */
export function unscannedFiles(root: string = SRC): string[] {
  return walk(root)
    .map((f) => posix(relative(root, f)))
    .filter((f) => !SCANNED_EXT.test(f))
}

export function offenders(pattern: RegExp, root: string = SRC): string[] {
  return guardedFiles(root).flatMap((file) =>
    readFileSync(join(root, file), 'utf8')
      .split('\n')
      .flatMap((line, i) => (pattern.test(line) ? [`${file}:${i + 1}: ${line.trim()}`] : [])),
  )
}

/** Hex colors and every CSS color function. */
export const COLOR_LITERAL =
  /#[0-9a-fA-F]{3,8}\b|\b(?:rgba?|hsla?|oklch|oklab|lab|lch|hwb|color|color-mix)\(/

/** Arbitrary bracket values holding a length or a calc() expression. */
export const ARBITRARY_SIZE =
  /\b[a-z][a-z-]*-\[[^\]]*(?:\d(?:px|rem|em|vh|vw|svh|dvh|ch|pt|%)|calc\()/

/** px/rem/em lengths. */
export const LENGTH_VALUE = /\d(?:px|rem|em)\b/

/** Tailwind default-palette utilities (white, black, red-500 and so on). */
export const PALETTE_CLASS =
  /\b(?:bg|text|border(?:-[trblxyse])?|ring|ring-offset|fill|stroke|outline|from|to|via|divide|shadow|decoration|accent|caret|placeholder)-(?:white|black|(?:slate|gray|zinc|neutral|stone|red|orange|amber|yellow|lime|green|emerald|teal|cyan|sky|blue|indigo|violet|purple|fuchsia|pink|rose)-\d{2,3})\b/

/** Lengths inside inline style attributes and <style> blocks of .vue files. */
export function inlineStyleOffenders(root: string = SRC): string[] {
  return guardedFiles(root)
    .filter((f) => /\.(vue|ts|tsx)$/.test(f))
    .flatMap((file) => {
      const text = readFileSync(join(root, file), 'utf8')
      const chunks: string[] = []
      for (const m of text.matchAll(/\bstyle\s*=\s*"([^"]*)"/g)) chunks.push(m[1] ?? '')
      if (file.endsWith('.vue')) {
        for (const m of text.matchAll(/<style[^>]*>([\s\S]*?)<\/style>/g)) chunks.push(m[1] ?? '')
      }
      return chunks
        .filter((c) => LENGTH_VALUE.test(c))
        .map((c) => `${file}: ${c.trim().slice(0, 80)}`)
    })
}

const PAGE_FILE = /^features\/[^/]+\/pages\/.+\.vue$/
const PAGE_EXEMPT = /^features\/(landing|auth|onboarding)\/pages\//

/** Pages (features/<f>/pages/*.vue) that must use PageHeader: all but landing, auth and onboarding. */
export function pagesRequiringHeader(files: string[]): string[] {
  return files.filter((f) => PAGE_FILE.test(f) && !PAGE_EXEMPT.test(f))
}

/** Returns the pages (relative to root) that do not render a <PageHeader>. */
export function pagesMissingHeader(root: string = SRC): string[] {
  return pagesRequiringHeader(guardedFiles(root)).filter(
    (f) => !/<PageHeader\b/.test(readFileSync(join(root, f), 'utf8')),
  )
}
