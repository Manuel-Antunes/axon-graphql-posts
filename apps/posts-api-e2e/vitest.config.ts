import { defineConfig } from 'vitest/config';

export default defineConfig({
  test: {
    include: ['src/specs/**/*.e2e.spec.ts'],

    globalSetup: ['./src/global-setup.ts'],

    fileParallelism: false,
    pool: 'forks',
    poolOptions: { forks: { singleFork: true } },

    testTimeout: 90_000,
    hookTimeout: 300_000,
    teardownTimeout: 60_000,

    retry: 0,

    reporters: process.env.CI ? ['default', 'junit'] : ['default'],
    outputFile: { junit: 'target/test-results/junit.xml' },
  },
});
