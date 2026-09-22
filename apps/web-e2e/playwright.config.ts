import { defineConfig, devices } from '@playwright/test';

const WEB_PORT = process.env.WEB_PORT ?? '4300';
const WEB_URL = process.env.WEB_URL ?? `http://localhost:${WEB_PORT}`;

process.env.WEB_PORT = WEB_PORT;
process.env.WEB_URL = WEB_URL;

export default defineConfig({
  testDir: './src/specs',
  globalSetup: './src/global-setup.ts',
  globalTeardown: './src/global-teardown.ts',
  outputDir: './target/playwright',

  fullyParallel: false,
  workers: 1,
  forbidOnly: !!process.env.CI,
  retries: 0,

  timeout: 120_000,
  globalTimeout: 30 * 60_000,
  expect: { timeout: 30_000 },

  reporter: process.env.CI
    ? [
        ['list'],
        ['html', { outputFolder: 'target/playwright-report', open: 'never' }],
        ['junit', { outputFile: 'target/test-results/junit.xml' }],
      ]
    : [['list']],

  use: {
    baseURL: WEB_URL,
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure',
  },

  projects: [{ name: 'chromium', use: { ...devices['Desktop Chrome'] } }],
});
