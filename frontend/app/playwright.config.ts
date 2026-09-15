import { defineConfig } from '@playwright/test';

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  use: {
    baseURL: process.env.DAC_APP_URL ?? 'http://localhost:3000',
    trace: 'on-first-retry',
  },
  webServer: process.env.DAC_APP_URL
    ? undefined
    : { command: 'yarn dev', url: 'http://localhost:3000', reuseExistingServer: true },
});
