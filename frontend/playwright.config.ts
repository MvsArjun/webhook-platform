import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { defineConfig, devices } from '@playwright/test'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..')

export default defineConfig({
  testDir: './e2e',
  testMatch: '**/*.spec.ts',
  forbidOnly: !!process.env.CI,
  fullyParallel: false,
  workers: 1,
  retries: 0,
  timeout: 90_000,
  expect: { timeout: 12_000 },
  reporter: [['./e2e/safe-reporter.mjs']],
  outputDir: path.join(root, 'work', 'playwright-results'),
  preserveOutput: 'never',
  use: {
    ...devices['Desktop Chrome'],
    baseURL: process.env.E2E_UI_URL,
    headless: true,
    trace: 'off',
    screenshot: 'off',
    video: 'off',
    actionTimeout: 15_000,
    navigationTimeout: 20_000,
  },
})
