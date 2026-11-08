import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'
import tailwindcss from '@tailwindcss/vite'

const datesSpec = 'src/shared/lib/dates.spec.ts'
const timezones = ['America/Sao_Paulo', 'Asia/Tokyo']

export default defineConfig({
  plugins: [vue(), tailwindcss()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: {
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    projects: [
      {
        extends: true,
        test: {
          name: 'unit',
          environment: 'jsdom',
          globals: false,
          include: ['src/**/*.spec.ts'],
          exclude: [datesSpec],
          setupFiles: ['src/test/setup.ts'],
        },
      },
      // The business-date tests must hold in a negative and a positive UTC offset.
      ...timezones.map((tz) => ({
        extends: true as const,
        test: {
          name: `dates ${tz}`,
          environment: 'jsdom',
          globals: false,
          include: [datesSpec],
          setupFiles: ['src/test/setup.ts'],
          env: { TZ: tz },
        },
      })),
    ],
  },
})
