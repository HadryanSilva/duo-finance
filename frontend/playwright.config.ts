/**
 * End-to-end tests against the real backend and the Vite dev server.
 *
 * Prerequisites:
 * - The dev Postgres is running: `docker compose -f ../infra/docker-compose.dev.yml up -d`.
 * - Chromium is installed once: `npm run e2e:install`.
 *
 * Playwright starts the backend (profiles `dev,e2e`: open registration, logged e-mails exposed at
 * `/api/v1/__e2e/outbox`) and Vite, or reuses them when they are already running.
 * Every test registers users with unique e-mails, so no database cleanup is needed.
 */
import { defineConfig, devices } from '@playwright/test'

const mvnw = process.platform === 'win32' ? '.\\mvnw.cmd' : './mvnw'

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: !!process.env.CI,
  retries: process.env.CI ? 1 : 0,
  reporter: process.env.CI ? 'github' : 'list',
  // The Vite dev server compiles a lazy route on its first visit; with parallel workers that
  // can take longer than the 5 s default.
  expect: { timeout: 15_000 },
  use: {
    baseURL: 'http://localhost:5173',
    locale: 'pt-BR',
    trace: 'retain-on-failure',
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'], viewport: { width: 1280, height: 800 } },
    },
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
  webServer: [
    {
      command: `${mvnw} spring-boot:run -Dspring-boot.run.profiles=dev,e2e`,
      cwd: '../backend',
      url: 'http://localhost:8080/api/actuator/health',
      timeout: 180_000,
      reuseExistingServer: !process.env.CI,
    },
    {
      command: 'npm run dev -- --port 5173 --strictPort',
      url: 'http://localhost:5173',
      timeout: 60_000,
      reuseExistingServer: !process.env.CI,
    },
  ],
})
