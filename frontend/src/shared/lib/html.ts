const ENTITIES: Record<string, string> = {
  '&': '&amp;',
  '<': '&lt;',
  '>': '&gt;',
  '"': '&quot;',
  "'": '&#39;',
}

/**
 * Escapes text for places that take an HTML string, such as an ECharts tooltip formatter
 * (ECharts writes it with innerHTML). Templates never need this: Vue escapes interpolations.
 */
export function escapeHtml(text: string): string {
  return text.replace(/[&<>"']/g, (c) => ENTITIES[c] ?? c)
}
