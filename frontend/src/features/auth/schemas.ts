import { z } from 'zod'

type T = (key: string) => string

const email = (t: T) =>
  z.string().trim().min(1, t('validation.NotBlank')).email(t('validation.Email'))
// Mirrors the backend: password 8-128 characters.
const password = (t: T) => z.string().min(8, t('validation.Size')).max(128, t('validation.Size'))

export const loginSchema = (t: T) =>
  z.object({ email: email(t), password: z.string().min(1, t('validation.NotBlank')) })

export const registerSchema = (t: T, accessCodeRequired: boolean) =>
  z
    .object({
      email: email(t),
      password: password(t),
      // 1-60 characters after trimming.
      displayName: z.string().trim().min(1, t('validation.NotBlank')).max(60, t('validation.Size')),
      locale: z.enum(['pt-BR', 'en'], { message: t('validation.Invalid') }),
      acceptTerms: z.boolean().refine((v) => v, t('validation.AssertTrue')),
      accessCode: z.string().trim().optional(),
    })
    .superRefine((v, ctx) => {
      if (accessCodeRequired && !v.accessCode) {
        ctx.addIssue({ code: 'custom', path: ['accessCode'], message: t('validation.NotBlank') })
      }
    })

export const forgotPasswordSchema = (t: T) => z.object({ email: email(t) })

export const resetPasswordSchema = (t: T) => z.object({ newPassword: password(t) })
