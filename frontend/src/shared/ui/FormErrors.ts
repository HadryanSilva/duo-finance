import { isApiError } from '../api/problem'
import { i18n } from '../i18n'

/**
 * Maps the API's field errors (`errors[]` of a 400) to translated messages, one per field,
 * through `setFieldError`. Returns true when at least one field error was applied.
 */
export function applyFieldErrors(
  error: unknown,
  setFieldError: (field: string, message: string) => void,
): boolean {
  if (!isApiError(error) || error.fieldErrors.length === 0) return false
  const { t, te } = i18n.global
  for (const { field, code } of error.fieldErrors) {
    setFieldError(
      field,
      te(`validation.${code}`) ? t(`validation.${code}`) : t('validation.Invalid'),
    )
  }
  return true
}
