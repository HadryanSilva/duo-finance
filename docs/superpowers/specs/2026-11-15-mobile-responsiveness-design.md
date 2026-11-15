# Mobile responsiveness fixes — design

**Date:** 2026-11-15 · **Size:** small change (frontend only)

## Intent

People opening duo-finance on a phone should be able to use it comfortably, not just see screens that
fit the width. The mobile foundation already exists (bottom nav with "+", bottom-sheet side panels,
16px inputs below `sm`). This change fixes four concrete pain points found in the code.

## Goals

1. **iPhone safe area** — `viewport-fit=cover` in `index.html`; the bottom nav and anything pinned to
   the bottom (bottom sheets, `main` bottom padding) reserve `env(safe-area-inset-bottom)` so the home
   indicator never covers controls.
2. **Tall dialogs scroll** — `DialogContent` gets a max height tied to the viewport (`dvh`) with its
   content scrolling, so nothing is cut off on small screens.
3. **Transaction filters (approach A)** — below `md`: the search field stays visible, next to a
   "Filtros" button that shows how many filters are active ("Filtros · 2") and opens a bottom sheet
   (the existing `SidePanel`) with dates, kind, category, account and author. From `md` up the current
   grid stays as it is. Filter selects switch to `nativeSelectClass` (16px below `sm`, no iOS zoom).
   Filters still apply instantly through the URL, as today.
4. **Row actions menu** — below `md`, the edit / archive / delete icon buttons on bank account,
   category and recurring rule rows collapse into a "⋯" button opening the existing dropdown menu.
   From `md` up the icons stay. Accessible names keep the item name (e.g. "Ações para Nubank").

## Non-goals

- No changes to the dashboard, import review, settings or other pages beyond the four points.
- No route, flow or API changes; no new dependencies.

## Anti-goals

- Desktop must look and behave exactly as before.
- Active filters must never be silently hidden on mobile — the count on the button is mandatory.

## Approaches considered (filters)

- **A. Search + "Filtros" button opening a bottom sheet** — list visible right away, familiar from
  banking apps; active filters less visible, offset by the count. **Picked by the user.**
- B. Collapsible grid in place — simplest, but when open it still pushes the list down.
- C. Horizontally scrolling chips, one picker per filter — elegant but much more work, and date
  ranges fit chips poorly.

For row actions the user picked the "⋯" menu over keeping icons with wrapping names (touch targets
stay cramped with three icons).

## Left to the builder

- Exact markup for switching mobile/desktop (CSS `md:` classes vs `useMediaQuery`, as `SidePanel`
  already does) — prefer CSS where it avoids duplicate component state.
- Whether a "Limpar filtros" action belongs in the sheet (nice to have, only if trivial).
- Keep the `design-usage.spec.ts` guard green; add unit tests for the filter count and the menu, and
  extend existing e2e/specs where they touch these rows.
