# Component polish — design (short)

**Date:** 2026-11-26 · **Size:** small change

## Intent

Some form controls still show raw browser chrome and read as improvised ("gambiarra"). The worst is the native
select arrow, which sits against the right edge. This round polishes those controls so they match the indigo
fintech design system. It does not change flows or layout.

## Goals

1. **Selects:** create a `shared/ui/NativeSelect.vue` that keeps the native `<select>` and adds `appearance-none`,
   a lucide `ChevronDown` placed 12px from the right edge (pointer-events-none, muted colour) and `pr-9` padding.
   It keeps the current height, focus ring, `aria-invalid` red border, disabled state and the 16px-below-md font
   size (stops iOS zooming on focus). All 13 `<select>` uses switch to it, `SelectField` included, and
   `native-select.ts` is removed.
2. **File input:** create a `shared/ui/FileInput.vue`. The real `<input type="file">` stays (visually hidden, still
   labelled and focusable through the button). It shows an outline `Button` ("Escolher arquivo") and the chosen
   file name, or "Nenhum arquivo selecionado". Both strings are added in pt and en. Used in `ImportUploadStep`.
3. **Checkbox:** create a `shared/ui/Checkbox.vue`. It stays a native `<input type="checkbox">` with
   `appearance-none`: a bordered rounded box, `bg-primary` when checked with a white lucide `Check` on top, the
   standard focus ring, a red border when `aria-invalid` and a dimmed disabled state. Attributes pass through so
   `v-model`, vee-validate attrs, `aria-*` and `@change` keep working. Used in RegisterPage, GoogleSignupPage and
   ImportReviewRow.
4. **Date inputs:** keep the native picker. A global rule in `app/styles.css` tones down
   `::-webkit-calendar-picker-indicator` (lower opacity, full on hover, pointer cursor). Firefox keeps its own icon.
5. **Import help `<details>`:** `summary` gets `list-none` (and the webkit marker hidden), plus a lucide
   `ChevronRight` that rotates 90° when open (`group-open`).
6. **Guard:** extend `src/test/design-usage.spec.ts` so a raw `<select` or `type="checkbox"` outside these
   components fails.

## Non-goals / anti-goals

- No custom dropdown lists (option B, the reka Select, was rejected). The open list stays native, which is better
  on mobile.
- No custom date picker.
- No flow, layout or route changes. Keyboard behaviour and existing tests must keep working.
- Must look right in light and dark themes.

## Approaches considered

- **A. Restyle native controls (chosen):** cheap, keeps native keyboard and mobile behaviour, and fixes what the
  user sees.
- **B. Replace with reka-ui custom components:** themed open lists, but changes behaviour in 13 places, is worse
  on mobile and needs test rewrites. Rejected by the user.

## Calls made by Claude (not explicitly decided by the user)

- Checkbox stays native (not reka Checkbox), to keep form bindings untouched.
- Date picker icon is only toned down, not replaced.
- The guard test is extended to cover selects and checkboxes.

## Left to the builder

Exact icon size and colour tokens, the file-name truncation style and how `FileInput` exposes a reset.
