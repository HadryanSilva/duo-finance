import { readdirSync, readFileSync, statSync } from 'node:fs'
import { dirname, join, relative, sep } from 'node:path'
import { fileURLToPath } from 'node:url'

export const SRC = join(dirname(fileURLToPath(import.meta.url)), '..')

const EXEMPT = new Set(['app/styles.css', 'shared/api/schema.ts'])

function walk(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const full = join(dir, name)
    return statSync(full).isDirectory() ? walk(full) : [full]
  })
}

function posix(path: string): string {
  return path.split(sep).join('/')
}

/** Every .vue/.ts file under root that the guard scans (relative posix paths). */
export function guardedFiles(root: string = SRC): string[] {
  return walk(root)
    .map((f) => posix(relative(root, f)))
    .filter((f) => /\.(vue|ts)$/.test(f) && !f.endsWith('.spec.ts') && !EXEMPT.has(f))
}

export function offenders(pattern: RegExp, root: string = SRC): string[] {
  return guardedFiles(root).flatMap((file) =>
    readFileSync(join(root, file), 'utf8')
      .split('\n')
      .flatMap((line, i) => (pattern.test(line) ? [`${file}:${i + 1}: ${line.trim()}`] : [])),
  )
}

export const COLOR_LITERAL = /#[0-9a-fA-F]{3,8}\b|rgba?\(|hsla?\(/
export const ARBITRARY_SIZE = /\b(?:text|p[trblxy]?|m[trblxy]?|gap|w|h|rounded)-\[[^\]]+\]/

const PAGE_FILE = /^features\/[^/]+\/pages\/.+\.vue$/
const PAGE_EXEMPT = /^features\/(landing|auth|onboarding)\/pages\//

/** Pages (features/<f>/pages/*.vue) that must use PageHeader: all but landing, auth and onboarding. */
export function pagesRequiringHeader(files: string[]): string[] {
  return files.filter((f) => PAGE_FILE.test(f) && !PAGE_EXEMPT.test(f))
}

/** Returns the pages (relative to root) that do not use PageHeader. */
export function pagesMissingHeader(root: string = SRC): string[] {
  return pagesRequiringHeader(guardedFiles(root)).filter(
    (f) => !/\bPageHeader\b/.test(readFileSync(join(root, f), 'utf8')),
  )
}
