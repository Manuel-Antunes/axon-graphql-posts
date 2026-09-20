import vitest from '@vitest/eslint-plugin';

import baseConfig from '../../eslint.base.config.mjs';

export default [
  ...baseConfig,

  {
    files: ['src/specs/**/*.e2e.spec.ts'],
    plugins: { vitest },
    rules: {
      'vitest/expect-expect': 'error',

      'vitest/no-focused-tests': 'error',
      'vitest/no-disabled-tests': 'warn',

      'vitest/no-identical-title': 'error',

      'vitest/no-standalone-expect': 'error',

      'vitest/prefer-to-be': 'error',
    },
  },

  {
    files: ['src/support/**/*.ts', 'src/global-setup.ts'],
    rules: {
      'no-console': 'off',
    },
  },
];
