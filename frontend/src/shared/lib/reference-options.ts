export type Option = { id: string; label: string }
type Ref = { id: string; name: string; archived: boolean }
type Item = { id: string; name: string; archived: boolean }

/**
 * Select options for a category or bank account: active items only, plus any current reference
 * that is archived (labelled as such) so editing an old record keeps its value visible.
 */
export function referenceOptions(
  items: readonly Item[] | undefined,
  currents: readonly (Ref | null | undefined)[],
  archivedLabel: (name: string) => string,
): Option[] {
  const options = (items ?? []).filter((i) => !i.archived).map((i) => ({ id: i.id, label: i.name }))
  for (const current of currents) {
    if (current?.archived && !options.some((o) => o.id === current.id)) {
      options.push({ id: current.id, label: archivedLabel(current.name) })
    }
  }
  return options
}
