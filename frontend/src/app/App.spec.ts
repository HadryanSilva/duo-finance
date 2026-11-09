import { screen, waitFor } from '@testing-library/dom'
import { toast } from 'vue-sonner'
import { afterEach, describe, expect, it } from 'vitest'
import App from '@/app/App.vue'
import { render } from '@/test/render'

afterEach(() => document.documentElement.classList.remove('dark'))

const toaster = () => document.querySelector('[data-sonner-toaster]')

describe('App toaster', () => {
  it('toast_showsFromAnywhereInTheLightTheme', async () => {
    await render(App, { route: '/' })
    toast('Salvo com sucesso')
    expect(await screen.findByText('Salvo com sucesso')).toBeTruthy()
    expect(document.querySelectorAll('[data-sonner-toaster]')).toHaveLength(1)
    expect(toaster()?.getAttribute('data-sonner-theme')).toBe('light')
  })

  it('toast_followsTheDarkClass', async () => {
    await render(App, { route: '/' })
    toast('Em escuro')
    await screen.findByText('Em escuro')
    document.documentElement.classList.add('dark')
    await waitFor(() => expect(toaster()?.getAttribute('data-sonner-theme')).toBe('dark'))
  })
})
