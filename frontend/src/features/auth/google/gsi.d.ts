/** Minimal typings for Google Identity Services (https://accounts.google.com/gsi/client). */
declare namespace google.accounts.id {
  interface CredentialResponse {
    credential: string
  }
  interface IdConfiguration {
    client_id: string
    callback: (response: CredentialResponse) => void
    ux_mode?: 'popup' | 'redirect'
  }
  interface ButtonConfiguration {
    type?: 'standard' | 'icon'
    theme?: 'outline' | 'filled_blue' | 'filled_black'
    size?: 'large' | 'medium' | 'small'
    text?: 'signin_with' | 'signup_with' | 'continue_with' | 'signin'
    shape?: 'rectangular' | 'pill'
    width?: number
    locale?: string
  }
  function initialize(config: IdConfiguration): void
  function renderButton(parent: HTMLElement, options: ButtonConfiguration): void
}
