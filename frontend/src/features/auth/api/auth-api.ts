import type { Http } from '@/shared/api/http'
import type { AuthResponse, InvitationPreviewResponse, RegisterRequest } from '@/shared/api/types'

export const login = (http: Http, body: { email: string; password: string }) =>
  http.post<AuthResponse>('/auth/login', body)

export const register = (http: Http, body: RegisterRequest) =>
  http.post<AuthResponse>('/auth/register', body)

export const verifyEmail = (http: Http, token: string) =>
  http.post<void>('/auth/verify-email', { token })

export const resendVerification = (http: Http) => http.post<void>('/auth/resend-verification')

export const forgotPassword = (http: Http, email: string) =>
  http.post<void>('/auth/forgot-password', { email })

export const resetPassword = (http: Http, token: string, newPassword: string) =>
  http.post<void>('/auth/reset-password', { token, newPassword })

export const invitationPreview = (http: Http, token: string) =>
  http.get<InvitationPreviewResponse>(`/account/invitations/${encodeURIComponent(token)}`)

export const acceptInvitation = (http: Http, token: string, confirmDiscard: boolean) =>
  http.post<AuthResponse>(`/account/invitations/${encodeURIComponent(token)}/accept`, {
    confirmDiscard,
  })
