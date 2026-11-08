import type { Http } from '@/shared/api/http'
import type { JoinWaitlistRequest } from '@/shared/api/types'

export const joinWaitlist = (http: Http, body: JoinWaitlistRequest) =>
  http.post<void>('/public/waitlist', body)
