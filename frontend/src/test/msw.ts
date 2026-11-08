import { setupServer } from 'msw/node'

/** Shared MSW server. Tests add handlers with `server.use(...)`; they are reset after each test. */
export const server = setupServer()
