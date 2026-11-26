# Component Polish Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace raw browser chrome on selects, the file input, checkboxes, date pickers and the import-help `<details>` with styling that matches the design system, keeping every control native.

**Architecture:** Three small shared components in `frontend/src/shared/ui/` (`NativeSelect.vue`, `Checkbox.vue`, `FileInput.vue`). Each wraps the native element, and every usage moves to them. The date picker and `<details>` get styling only (global CSS, and a chevron in the template). The design guard (`src/test/design-guard.ts`) gains a rule that blocks raw `<select`, `type="checkbox"` and `type="file"` outside their components.

**Tech Stack:** Vue 3.5 `<script setup>` + TS, Tailwind v4 tokens, `@lucide/vue` icons, vitest + @testing-library/vue (`src/test/render.ts`), vue-i18n (`src/shared/i18n/locales/{pt-BR,en}.json`).

**Spec:** `docs/superpowers/specs/2026-11-26-component-polish-design.md`

## Global Constraints

- Controls stay native: no reka Select/Checkbox, no custom date picker, and no flow, layout or route changes.
- Selects keep `h-9`, the `rounded-md border border-input bg-background` look, the focus ring `focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50`, `aria-invalid:border-destructive`, and `text-base md:text-sm` (16px below md so iOS does not zoom).
- Chevron: lucide `ChevronDown`, `size-4`, `text-muted-foreground`, `pointer-events-none`, `aria-hidden`, 12px from the right (`right-3`). The select gets `pl-3 pr-9 appearance-none`.
- Only design tokens: the guard forbids hex/rgb, palette classes (`text-white` and similar) and arbitrary sizes. Use `text-primary-foreground`, `bg-primary` and so on.
- Copy: `common.file.choose` = "Escolher arquivo" / "Choose file"; `common.file.none` = "Nenhum arquivo selecionado" / "No file chosen". pt-BR and en key sets must match (`i18n.spec.ts`).
- Light and dark themes must both look right.
- Commands run from `frontend/`. Prefix them with `rtk` (user rule). Each commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

## Review Focus

1. **A parent changes a select's value externally** (for example, clearing transaction filters resets `kind` to `''`): the select must show the new value. Pinned in Task 1 (rerender test).
2. **The salary picker reverts its select on a failed save** by writing to `event.target.value`: this must keep working through `NativeSelect` because `@change` passes through. Covered by the existing `SalaryCategoryPicker`/Goals specs, which must stay green (Task 2).
3. **The parent resets the file to `null`** (new import after commit): the name goes back to "Nenhum arquivo selecionado" and the input is cleared, so picking the same file again fires `change`. Pinned in Task 4.
4. **Locked/disabled state** (import commit running): the select, checkbox and file "button" look disabled and do not react. Pinned with disabled tests in Tasks 1, 3 and 4, and checked visually in Task 5.
5. **Keyboard use:** Tab reaches the hidden file input and its visible button shows the focus ring; the checkbox shows a focus ring. Pinned in Task 4 (input focusable by Tab), with the ring checked visually in Task 5.

---

### Task 1: NativeSelect component

**Files:**
- Create: `frontend/src/shared/ui/NativeSelect.vue`
- Test: `frontend/src/shared/ui/NativeSelect.spec.ts`

**Interfaces:**
- Produces: `NativeSelect.vue` with `defineOptions({ inheritAttrs: false })` and `const model = defineModel<string>()`. Its props are just `class?: HTMLAttributes['class']`, applied to the wrapper `<div class="relative">`. Every other attr (`id`, `disabled`, `aria-*`, `onChange`, vee-validate attrs) goes to the `<select>` via `v-bind="$attrs"`. Options come through the default slot. Callers bind the value with `v-model` or `:model-value`/`@update:model-value`, never with a raw `:value`. A raw `@change` still reaches the `<select>`.

- [ ] **Step 1: Write the failing tests** in `NativeSelect.spec.ts` (render inside a small wrapper with `<option value="a">A</option><option value="b">B</option>` and a `<label for="s">Field</label>`):
  - `renders a decorative chevron`: `container.querySelector('svg[aria-hidden="true"]')` is not null.
  - `v-model updates on choice`: `await user.selectOptions(getByLabelText('Field'), 'b')` → the emitted `update:modelValue` equals `['b']`.
  - `follows an external model change`: render with `modelValue: 'a'`, `rerender({ modelValue: 'b' })` → `(getByLabelText('Field') as HTMLSelectElement).value === 'b'`.
  - `passes attrs to the select and class to the wrapper`: `id="s" disabled aria-invalid="true" class="w-40"` → the select is `toBeDisabled()`, has `aria-invalid="true"` and has class `appearance-none`; `select.parentElement` has class `w-40`.
  - `forwards a raw change listener`: an `onChange` spy is called once with an `Event` whose `target` is the select.
- [ ] **Step 2: Run, expect failure:** `rtk vitest run src/shared/ui/NativeSelect.spec.ts` → FAIL (module not found).
- [ ] **Step 3: Implement `NativeSelect.vue`.** The select class is `h-9 w-full appearance-none rounded-md border border-input bg-background pl-3 pr-9 text-base outline-none focus-visible:border-ring focus-visible:ring-3 focus-visible:ring-ring/50 aria-invalid:border-destructive disabled:cursor-not-allowed disabled:opacity-50 md:text-sm`. The chevron is `ChevronDown` with `pointer-events-none absolute right-3 top-1/2 size-4 -translate-y-1/2 text-muted-foreground` and `aria-hidden="true"`. Combine the wrapper class with `cn` from `@/shared/lib/utils`.
- [ ] **Step 4: Run, expect pass:** same command → PASS.
- [ ] **Step 5: Commit** `feat(web): NativeSelect with a padded chevron`.

### Task 2: Move every select to NativeSelect, guard raw selects

**Files:**
- Modify: `src/shared/ui/SelectField.vue` (drop the `SELECT` const)
- Modify: `src/features/auth/pages/RegisterPage.vue`, `src/features/landing/components/WaitlistForm.vue`, `src/features/bank-accounts/components/BankAccountForm.vue` (2 selects), `src/features/categories/components/CategoryForm.vue`, `src/features/onboarding/components/StepFirstBankAccount.vue`. These use `v-model`, which stays as-is on `<NativeSelect>`, with the class strings removed.
- Modify: `src/features/goals/components/SalaryCategoryPicker.vue`, `src/features/imports/components/ImportReviewRow.vue` (drop its `SELECT` const), `src/features/transactions/components/TransactionFilterFields.vue` (4 selects). These change `:value` to `:model-value`. Filters and the review row move their handler to `@update:model-value` (remove the `selectValue` helper; map `''` to `undefined` inline as today). `SalaryCategoryPicker` keeps `@change="onChange"` (it needs `event.target` to revert).
- Delete: `src/shared/ui/native-select.ts`
- Modify: `src/test/design-guard.ts`, `src/test/design-guard.spec.ts`

**Interfaces:**
- Consumes: `NativeSelect.vue` (Task 1).
- Produces, in `design-guard.ts`: `export const RAW_CONTROLS: { pattern: RegExp; allowedIn: string }[]` (starting with `{ pattern: /<select\b/, allowedIn: 'shared/ui/NativeSelect.vue' }`) and `export function rawControls(root: string = SRC): string[]`, which returns `file:line: text` for each pattern match in a `.vue` file other than its `allowedIn`. Tasks 3 and 4 append entries.

- [ ] **Step 1: Write the failing guard tests** in `design-guard.spec.ts`:
  - `rawControls flags a raw select outside NativeSelect` (temp-dir fixture, as the existing fixture tests do): `features/x/A.vue` containing `<select>` is reported, and `shared/ui/NativeSelect.vue` containing `<select>` is not.
  - `has no raw native controls outside their shared components`: `expect(rawControls()).toEqual([])`.
- [ ] **Step 2: Run, expect failure:** `rtk vitest run src/test/design-guard.spec.ts` → the second test fails, listing the 13 selects.
- [ ] **Step 3: Implement `rawControls`/`RAW_CONTROLS` and migrate every file listed above.**
- [ ] **Step 4: Run the whole suite and type check:** `rtk vitest run` → all pass (the existing Register, Waitlist, BankAccount, Category, Onboarding, Goals, Import and Transactions specs are the regression net); `rtk tsc` via `rtk npm run build` → no errors; `rtk npm run lint` → clean.
- [ ] **Step 5: Commit** `refactor(web): all selects use NativeSelect; guard raw selects`.

### Task 3: Checkbox component

**Files:**
- Create: `src/shared/ui/Checkbox.vue`, `src/shared/ui/Checkbox.spec.ts`
- Modify: `src/features/auth/pages/RegisterPage.vue`, `src/features/auth/pages/GoogleSignupPage.vue`, `src/features/imports/components/ImportReviewRow.vue`, `src/test/design-guard.ts` (add `{ pattern: /type="checkbox"/, allowedIn: 'shared/ui/Checkbox.vue' }`)

**Interfaces:**
- Produces: `Checkbox.vue` with `inheritAttrs: false`, `const model = defineModel<boolean>()` and a `class` prop that goes on the wrapper `<span class="relative inline-grid size-4 shrink-0 place-items-center">`. The input is `type="checkbox"` with `:checked="model"`, sets the model on `@change`, and takes the remaining attrs (`id`, `disabled`, `aria-*`, vee-validate attrs). The input class is `peer size-4 appearance-none rounded-sm border border-input bg-background transition-colors checked:border-primary checked:bg-primary focus-visible:ring-3 focus-visible:ring-ring/50 aria-invalid:border-destructive disabled:cursor-not-allowed disabled:opacity-50`. On top sits a lucide `Check` with `pointer-events-none absolute size-3 text-primary-foreground invisible peer-checked:visible` and `aria-hidden`.
- Callers: Register and Google signup use `v-model="acceptTerms"` with `v-bind="acceptTermsAttrs"` and keep `class="mt-0.5"`. ImportReviewRow uses `:model-value="row.include" @update:model-value="emit('include', $event)"`.

- [ ] **Step 1: Write the failing tests** in `Checkbox.spec.ts` (with `<label for="c">Accept</label>`):
  - `toggles the model`: `await user.click(getByLabelText('Accept'))` → emitted `update:modelValue` is `[true]`.
  - `reflects the model`: `modelValue: true` → `getByRole('checkbox')` is `toBeChecked()`; after `rerender({ modelValue: false })` it is not checked.
  - `passes attrs, not class, to the input`: `disabled aria-invalid="true" class="mt-0.5"` → the checkbox is disabled, has `aria-invalid="true"` and has class `appearance-none`; its `parentElement` has `mt-0.5`.
  - `disabled does not toggle`: clicking a disabled box emits nothing.
- [ ] **Step 2: Run, expect failure:** `rtk vitest run src/shared/ui/Checkbox.spec.ts` → FAIL.
- [ ] **Step 3: Implement `Checkbox.vue`, migrate the 3 usages and add the guard entry.**
- [ ] **Step 4: Run** `rtk vitest run` → all pass (Register, GoogleSignup and ImportPage specs included); `rtk npm run lint` → clean.
- [ ] **Step 5: Commit** `feat(web): themed Checkbox for terms and import rows`.

### Task 4: FileInput component

**Files:**
- Create: `src/shared/ui/FileInput.vue`, `src/shared/ui/FileInput.spec.ts`
- Modify: `src/features/imports/components/ImportUploadStep.vue` (replace the Label + raw input and drop `onFile`/`fileId`), both locale files (`common.file.choose`, `common.file.none`), `src/test/design-guard.ts` (add `{ pattern: /type="file"/, allowedIn: 'shared/ui/FileInput.vue' }`)

**Interfaces:**
- Produces: `FileInput.vue` with props `{ label: string; accept?: string; disabled?: boolean }` and `const model = defineModel<File | null>({ required: true })`. The markup is: a `Label :for="id"` with the label, then a row `flex min-w-0 items-center gap-3` holding:
  - `<input :id type="file" class="peer sr-only" :accept :disabled>`, which sets the model to `files[0] ?? null` on change;
  - a `<label :for="id" aria-hidden="true">` styled `cn(buttonVariants({ variant: 'outline', size: 'sm' }), 'cursor-pointer peer-focus-visible:border-ring peer-focus-visible:ring-3 peer-focus-visible:ring-ring/50 peer-disabled:pointer-events-none peer-disabled:opacity-50')`, with the text `t('common.file.choose')`;
  - `<span class="min-w-0 truncate text-sm text-muted-foreground">` showing `model?.name ?? t('common.file.none')`.
  - A `watch` on the model clears `input.value` when the model becomes `null`.
- ImportUploadStep: `<FileInput v-model="file" :label="t('imports.upload.file')" accept=".csv,text/csv" :disabled="locked" />`.

- [ ] **Step 1: Write the failing tests** in `FileInput.spec.ts` (pt-BR i18n, `label: 'Arquivo CSV'`):
  - `shows the empty text`: `getByText('Nenhum arquivo selecionado')`.
  - `shows the chosen file name`: `await user.upload(getByLabelText('Arquivo CSV'), new File(['x'], 'extrato.csv'))` → emitted `update:modelValue[0][0].name === 'extrato.csv'`; after `rerender({ modelValue: thatFile })`, `getByText('extrato.csv')`.
  - `clears when the model resets`: after the upload and the rerender with the file, `rerender({ modelValue: null })` → `input.files` has length 0 and `getByText('Nenhum arquivo selecionado')`.
  - `is reachable by keyboard`: `await user.tab()` → `getByLabelText('Arquivo CSV')` is `toHaveFocus()`.
  - `disabled blocks uploads`: `disabled: true` → the input is disabled and `user.upload` emits nothing.
- [ ] **Step 2: Run, expect failure:** `rtk vitest run src/shared/ui/FileInput.spec.ts` → FAIL.
- [ ] **Step 3: Implement it, add the locale keys, migrate ImportUploadStep and add the guard entry.**
- [ ] **Step 4: Run** `rtk vitest run` → all pass (`ImportPage.spec.ts` uses `getByLabelText('Arquivo CSV')` and must stay green unchanged; `i18n.spec.ts` checks key parity); `rtk npm run lint` → clean.
- [ ] **Step 5: Commit** `feat(web): FileInput with button and file name`.

### Task 5: Date picker icon, help chevron, visual check

**Files:**
- Modify: `src/app/styles.css` (base layer)
- Modify: `src/features/imports/components/ImportUploadStep.vue` (`<details>`)

- [ ] **Step 1: Date indicator.** In `styles.css`, add a base rule: `input[type='date']::-webkit-calendar-picker-indicator { cursor: pointer; opacity: 0.6; transition: opacity 150ms }` with `:hover` at `opacity: 1`. The `color-scheme` already set per theme keeps the icon legible in dark mode.
- [ ] **Step 2: Details chevron.** Add `class="group"` to `<details>`. The `summary` becomes `flex cursor-pointer list-none items-center gap-1 font-medium text-primary [&::-webkit-details-marker]:hidden`, with a leading lucide `ChevronRight` set to `size-4 transition-transform group-open:rotate-90` and `aria-hidden`. If the guard rejects the `[&::-webkit-details-marker]` variant, put `summary::-webkit-details-marker { display: none }` in `styles.css` instead.
- [ ] **Step 3: Run** `rtk vitest run` and `rtk npm run lint` and `rtk npm run build` → all clean.
- [ ] **Step 4: Visual check** (use the `run` skill to start the app; screenshot at 1280px and 390px, light and dark): the selects on Transações (filters), Contas and Categorias forms, and Cadastro (locale); the import page with an empty file, a chosen long file name (truncates, row does not overflow at 390px), the locked state and the help open/closed; the terms checkbox unchecked, checked, focused by Tab and in its error state; the date fields in the transaction form. Fix anything off before committing.
- [ ] **Step 5: Commit** `style(web): calmer date picker icon and help chevron`.
- [ ] **Step 6: Finish** following the repo rule: push `feature/component-polish` and open a PR against `develop`.
