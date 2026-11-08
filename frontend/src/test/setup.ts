import { afterAll, afterEach, beforeAll } from 'vitest'
import { server } from './msw'
import { cleanup } from './render'

beforeAll(() => server.listen({ onUnhandledFrame: 'error' }))
afterEach(() => {
  cleanup()
  server.resetHandlers()
  localStorage.clear()
})
afterAll(() => server.close())
