import playwright from 'eslint-plugin-playwright';

import baseConfig from '../../eslint.base.config.mjs';

export default [
  ...baseConfig,

  {
    ...playwright.configs['flat/recommended'],
    files: ['src/specs/**/*.spec.ts'],
    rules: {
      ...playwright.configs['flat/recommended'].rules,

      'playwright/expect-expect': 'error',

      'playwright/no-focused-test': 'error',
      'playwright/no-skipped-test': 'warn',

      'playwright/no-standalone-expect': 'error',

      'playwright/valid-expect': 'error',
      'playwright/no-conditional-expect': 'error',
      'playwright/no-conditional-in-test': 'error',
    },
  },

  {
    files: ['src/fixtures/**/*.ts'],
    rules: {
      'no-empty-pattern': 'off',
    },
  },

  {
    files: ['src/support/**/*.ts', 'src/global-*.ts'],
    rules: {
      'no-console': 'off',
    },
  },
];
