import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import Logo from './Logo.vue'

describe('Logo', () => {
  it('renders two circles with an accessible name', () => {
    const wrapper = mount(Logo)
    expect(wrapper.findAll('circle')).toHaveLength(2)
    expect(wrapper.find('svg').attributes('role')).toBe('img')
    expect(wrapper.find('svg').attributes('aria-label')).toBe('Duo Finance')
  })

  it('scales with the size prop', () => {
    expect(
      mount(Logo, { props: { size: 'lg' } })
        .find('svg')
        .classes(),
    ).toContain('size-12')
  })
})
