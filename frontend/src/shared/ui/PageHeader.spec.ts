import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import PageHeader from './PageHeader.vue'

describe('PageHeader', () => {
  it('renders title, subtitle and actions slot', () => {
    const wrapper = mount(PageHeader, {
      props: { title: 'Transactions', subtitle: 'March' },
      slots: { actions: '<button>New</button>' },
    })
    expect(wrapper.find('h1').text()).toBe('Transactions')
    expect(wrapper.text()).toContain('March')
    expect(wrapper.find('button').text()).toBe('New')
  })

  it('omits optional parts when absent', () => {
    const wrapper = mount(PageHeader, { props: { title: 'Budgets' } })
    expect(wrapper.find('p').exists()).toBe(false)
  })
})
