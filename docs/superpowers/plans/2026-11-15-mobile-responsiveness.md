# Mobile Responsiveness Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Fix four mobile pain points: iPhone safe area, tall dialogs, transaction filters, and row action buttons.

**Architecture:** One shared breakpoint composable (`useIsDesktop`, `md` = 48rem) drives every mobile/desktop switch that would otherwise duplicate accessible names in the DOM; pure-CSS concerns (safe area, dialog height) become `@utility` rules in `src/app/styles.css`, the only file the design guard lets hold raw sizes. jsdom gets a `matchMedia` stub driven by `window.innerWidth` so existing specs keep running as desktop (1024px) and new specs opt into phone width (375px).

**Tech Stack:** Vue 3 + TypeScript, Tailwind v4, reka-ui (shadcn-vue), @vueuse/core, vue-i18n, Vitest + Testing Library + MSW.

**Spec:** `docs/superpowers/specs/2026-11-15-mobile-responsiveness-design.md`

## Global Constraints

- Desktop (≥ `md`, 48rem) must look and behave exactly as before.
- Active filters must never be silently hidden on mobile — the count on the button is mandatory.
- No route, flow or API changes; no new dependencies.
- `src/test/design-guard.spec.ts` must stay green: no hex, no arbitrary `-[…px|dvh|calc(]` classes, no static `style=""` outside `src/app/styles.css`.
- Every new i18n key exists in both `pt-BR.json` and `en.json` (i18n spec checks parity).
- All commands run from `frontend/` with `rtk` prefix (`rtk vitest`, `rtk lint`, `rtk tsc`).

## Review Focus

1. Resizing across `md` while a filter sheet or row menu is open — the component must swap without throwing or leaving a stuck overlay (`useMediaQuery` is reactive; SidePanel already relies on it).
2. "Limpar filtros" must keep the search text (`q`) and clear only the six sheet filters — a user who cleared filters and lost their search would be surprised. Pinned in Task 3.
3. Kind = TRANSFER hides the category field; the count must not include a stale `categoryId` (it is already cleared by `onKind`). Pinned in Task 3 (count derives from `filter`, which the URL already cleaned).
4. Archived rows: the mobile menu must offer "Desarquivar" instead of "Arquivar", same as the icons. Pinned in Task 4.
5. Finished recurring rules: the menu must omit activate/deactivate, as the icons do. Pinned in Task 4.

---

### Task 1: Breakpoint composable + test `matchMedia` stub

**Files:**
- Create: `frontend/src/shared/lib/breakpoints.ts`
- Modify: `frontend/src/shared/ui/SidePanel.vue` (use the composable)
- Modify: `frontend/src/test/setup.ts` (install stub)
- Test: `frontend/src/shared/lib/breakpoints.spec.ts`

**Interfaces:**
- Produces: `useIsDesktop(): Ref<boolean>` — `useMediaQuery('(min-width: 48rem)')`. `DESKTOP_QUERY = '(min-width: 48rem)'` exported.
- Produces (tests): after setup, `window.matchMedia(q)` returns `{ matches, media, addEventListener, removeEventListener, addListener, removeListener, onchange, dispatchEvent }` where `matches` is evaluated from `window.innerWidth` for `(min-width: Nrem)` (N × 16px) and `false` for any other query. Install with `Object.defineProperty(window, 'matchMedia', { configurable: true, writable: true, value })` so `vi.stubGlobal` in `theme.spec.ts` still overrides it.

- [ ] **Step 1: Write the failing test** — `breakpoints.spec.ts`:
  - `useIsDesktop_trueAt1024`: `window.innerWidth = 1024`; inside a component mounted with `@vue/test-utils` (or `effectScope`), `useIsDesktop().value === true`.
  - `useIsDesktop_falseAt375`: `window.innerWidth = 375` → `false`.
  - `afterEach` resets `window.innerWidth = 1024`.
- [ ] **Step 2: Run** `rtk vitest run src/shared/lib/breakpoints.spec.ts` — Expected: FAIL (module not found).
- [ ] **Step 3: Implement** `breakpoints.ts`, the stub in `setup.ts`, and switch `SidePanel.vue` to `useIsDesktop()`.
- [ ] **Step 4: Run the whole suite** `rtk vitest run` — Expected: all PASS (existing specs now see desktop width; SidePanel opens on the right in tests, role queries unaffected). If any spec relied on the bottom sheet, fix the spec by setting `innerWidth = 375`, not the component.
- [ ] **Step 5: Commit** `feat(ui): share the md breakpoint and stub matchMedia in tests`

### Task 2: Safe area + scrollable dialogs

**Files:**
- Modify: `frontend/index.html:6` — viewport becomes `width=device-width, initial-scale=1.0, viewport-fit=cover`
- Modify: `frontend/src/app/styles.css` — add utilities
- Modify: `frontend/src/app/components/BottomNav.vue` (`pb-safe` on `<nav>`)
- Modify: `frontend/src/app/layouts/AppLayout.vue` (`main`: replace `pb-24` with `pb-nav-safe`, keep `md:pb-8`)
- Modify: `frontend/src/shared/ui/sheet/SheetContent.vue` (bottom variant gets `pb-safe`)
- Modify: `frontend/src/shared/ui/dialog/DialogContent.vue` (add `max-h-dialog overflow-y-auto`)
- Test: `frontend/src/app/layouts/AppLayout.spec.ts`, `frontend/src/features/admin/components/PlainCodeDialog.spec.ts`

**Interfaces:**
- Produces CSS utilities (in `styles.css`):
  - `@utility pb-safe { padding-bottom: env(safe-area-inset-bottom); }`
  - `@utility pb-nav-safe { padding-bottom: calc(6rem + env(safe-area-inset-bottom)); }`
  - `@utility max-h-dialog { max-height: calc(100dvh - 2rem); }`

- [ ] **Step 1: Write failing tests**
  - In `AppLayout.spec.ts` "renders bottom nav…": `expect(bottom.classes()).toContain('pb-safe')`; and `expect(wrapper.get('main').classes()).toEqual(expect.arrayContaining(['pb-nav-safe', 'md:pb-8']))`.
  - In `PlainCodeDialog.spec.ts`: the element with `data-slot="dialog-content"` has classes `max-h-dialog` and `overflow-y-auto`.
  - Index check (add to `src/test/design-guard.spec.ts`): `readFileSync(INDEX_HTML)` contains `viewport-fit=cover`.
- [ ] **Step 2: Run** `rtk vitest run src/app/layouts src/features/admin src/test` — Expected: FAIL on the new assertions.
- [ ] **Step 3: Implement** the edits listed above. Bottom sheet: append `pb-safe` to the `side === 'bottom'` class string only.
- [ ] **Step 4: Run** `rtk vitest run` and `rtk npm run build` — Expected: PASS; build confirms Tailwind accepts the utilities.
- [ ] **Step 5: Commit** `feat(ui): respect the iPhone safe area and let tall dialogs scroll`

### Task 3: Transaction filters — search + "Filtros" sheet on mobile

**Files:**
- Create: `frontend/src/shared/ui/native-select.ts`
- Create: `frontend/src/features/transactions/components/TransactionFilterFields.vue`
- Modify: `frontend/src/features/transactions/components/TransactionFilters.vue`
- Modify: `frontend/src/features/transactions/api/transactions-api.ts`
- Modify: `frontend/src/shared/i18n/locales/pt-BR.json`, `en.json` (`transactions.filters.*`)
- Test: `frontend/src/features/transactions/api/transactions-api.spec.ts` (create if absent, else extend), `frontend/src/features/transactions/pages/TransactionsPage.spec.ts`

**Interfaces:**
- Consumes: `useIsDesktop()` (Task 1), `SidePanel` (`open`, `title`, `update:open`).
- Produces:
  - `nativeSelectClass: string` in `native-select.ts` = the current filter `SELECT` string with `text-sm` replaced by `text-base md:text-sm` (same rule as `Input.vue`). Filter selects use it; other forms are out of scope.
  - `SHEET_FILTER_KEYS = ['from', 'to', 'kind', 'categoryId', 'bankAccountId', 'createdBy'] as const` and `activeFilterCount(filter: TransactionFilter): number` (count of those keys with a truthy value; `q` excluded) in `transactions-api.ts`.
  - `TransactionFilterFields.vue`: props `{ filter: TransactionFilter }`, emits `change: [patch: Patch]`; multi-root (no wrapper), holds the two date fields and four selects plus their logic (`onKind`, category/account/member queries, `optionName`) moved verbatim from `TransactionFilters.vue`.
  - `TransactionFilters.vue` keeps the search logic and the `role="search"` form named "Filtros":
    - desktop: today's grid, with `<TransactionFilterFields>` in place of the six fields, search still `sm:col-span-2`.
    - mobile: form is `flex items-end gap-2` with the search `TextField` (flex-1) and an outline `Button` (type="button") showing `Filtros` plus `· N` when N > 0; its `aria-label` is `Filtros` or `Filtros, N ativo(s)`. It opens a `SidePanel` titled `Filtros` containing `<TransactionFilterFields>` in a `grid gap-3` and, when N > 0, a ghost "Limpar filtros" button that emits `{ from: undefined, to: undefined, kind: undefined, categoryId: undefined, bankAccountId: undefined, createdBy: undefined }`.
  - i18n keys (pt-BR / en):
    - `transactions.filters.open`: `"Filtros"` / `"Filters"`
    - `transactions.filters.openWithCount`: `"Filtros, {n} ativo | Filtros, {n} ativos"` / `"Filters, {n} active | Filters, {n} active"` (call `t(key, { n }, n)`)
    - `transactions.filters.clear`: `"Limpar filtros"` / `"Clear filters"`

- [ ] **Step 1: Write failing unit test** `activeFilterCount`:
  - `{}` → 0; `{ q: 'mer' }` → 0; `{ from: '2026-11-01', to: '2026-11-30' }` → 2; all six set + `q` → 6.
- [ ] **Step 2: Write failing page tests** in `TransactionsPage.spec.ts` (each sets `window.innerWidth = 375`, `afterEach` resets 1024):
  - `filters_mobile_showsSearchAndCountButton`: route `/transactions?kind=INCOME` (default month adds `from`/`to`) → within `search` "Filtros": `getByLabelText('Buscar')` exists, `queryByLabelText('Tipo')` is null, `getByRole('button', { name: 'Filtros, 3 ativos' })` exists with visible text containing `· 3`.
  - `filters_mobile_sheetChangesUrl`: click the button → dialog "Filtros" opens → `selectOptions(within(dialog).getByLabelText('Tipo'), 'EXPENSE')` → `router.currentRoute.value.query.kind === 'EXPENSE'`.
  - `filters_mobile_clearKeepsSearch`: route `/transactions?kind=INCOME&q=mer` → open sheet → click "Limpar filtros" → `query.from === ''`, `query.to === ''`, `query.kind` is undefined, `query.q === 'mer'`, and the button now reads `Filtros` (no count).
  - `filters_selects_use16pxBelowMd`: on desktop, the "Tipo" select's classes include `text-base` and `md:text-sm`.
- [ ] **Step 3: Run** `rtk vitest run src/features/transactions` — Expected: new tests FAIL.
- [ ] **Step 4: Implement** per Interfaces. Clearing emits `undefined` for all six keys; `queryPatch` already writes `from`/`to` as `''` (present-and-empty = "no date", so the default month does not come back) and drops the others.
- [ ] **Step 5: Run** `rtk vitest run` — Expected: all PASS, including the existing `filters_syncWithUrlAndDebounceSearch` (desktop).
- [ ] **Step 6: Commit** `feat(transactions): collapse filters into a sheet on mobile`

### Task 4: "⋯" row actions menu on mobile

**Files:**
- Create: `frontend/src/shared/ui/ActionsMenu.vue`
- Modify: `frontend/src/shared/ui/RowActions.vue`
- Modify: `frontend/src/features/bank-accounts/components/BankAccountRow.vue`, `frontend/src/features/categories/components/CategoryRow.vue` (pass `menu-label`)
- Modify: `frontend/src/features/recurring/components/RecurringRuleRow.vue`
- Modify: `pt-BR.json`, `en.json`
- Test: `BankAccountsPage.spec.ts`, `CategoriesPage.spec.ts`, `RecurringPage.spec.ts`

**Interfaces:**
- Consumes: `useIsDesktop()` (Task 1); `DropdownMenu*` from `@/shared/ui/dropdown-menu`; `Ellipsis` from `@lucide/vue`.
- Produces:
  - `ActionsMenu.vue`: props `{ label: string; items: { key: string; label: string; icon: Component; destructive?: boolean }[] }`, emits `select: [key: string]`. Renders a ghost `size="icon"` trigger with `aria-label=label` and an `Ellipsis` icon, `DropdownMenuContent align="end"`; each item shows icon + label; destructive items get `variant="destructive"` (supported by the local `DropdownMenuItem`).
  - `RowActions.vue`: new required prop `menuLabel: string`; desktop renders today's icons unchanged; mobile renders `ActionsMenu` with items `edit` (common.edit), `archive`/`unarchive` (by `archived`), `remove` (common.delete, destructive), mapping `select` to the existing emits.
  - `RecurringRuleRow.vue`: desktop icons unchanged; mobile `ActionsMenu` with `edit`, `toggle` (omitted when `rule.finished`; label `recurring.deactivateShort` / `recurring.activateShort` by `rule.active`, icons `Pause`/`Play`), `remove` (destructive).
  - i18n keys: `common.actionsFor`: `"Ações para {name}"` / `"Actions for {name}"`; `recurring.activateShort`: `"Ativar"` / `"Activate"`; `recurring.deactivateShort`: `"Desativar"` / `"Deactivate"`.
  - Rows pass `menuLabel = t('common.actionsFor', { name })` with `account.name`, `category.name`, `title`.

- [ ] **Step 1: Write failing tests** (phone width 375, reset after):
  - `bankAccounts_mobile_actionsMenuArchives`: no button named "Arquivar Nubank"; click "Ações para Nubank" → menu items are `['Editar', 'Arquivar', 'Excluir']` → click "Arquivar" → archive request for `b1`.
  - `bankAccounts_mobile_archivedRowOffersUnarchive`: with archived shown, "Ações para Antiga" menu contains "Desarquivar", not "Arquivar".
  - `categories_mobile_actionsMenuEdits`: click "Ações para <first category>" → "Editar" → edit panel opens.
  - `recurring_mobile_finishedRuleHasNoToggle`: a finished rule's menu items are `['Editar', 'Excluir']`; an active rule's are `['Editar', 'Desativar', 'Excluir']`.
- [ ] **Step 2: Run** `rtk vitest run src/features/bank-accounts src/features/categories src/features/recurring` — Expected: new tests FAIL; existing desktop tests still PASS.
- [ ] **Step 3: Implement** per Interfaces.
- [ ] **Step 4: Run** `rtk vitest run`, `rtk lint`, `rtk npm run build` — Expected: all PASS, no lint or type errors.
- [ ] **Step 5: Commit** `feat(ui): fold row actions into a menu on mobile`

### Task 5: Manual check on a phone viewport

- [ ] **Step 1:** Run the app (`run` skill or `rtk npm run dev` with the backend up) and check at 375×812 in device emulation: bottom nav clears the home-indicator area (emulate an iPhone with safe area), a long dialog scrolls, the filter sheet opens and its count matches, and the "⋯" menus work on the three pages. At 1280px everything matches `main`.
- [ ] **Step 2:** Note anything off; fix under the owning task's scope and commit.
