import baseConfig from './eslint.base.config.mjs';

export default [
  ...baseConfig,

  {
    files: ['**/*.json'],
    languageOptions: {
      parser: await import('jsonc-eslint-parser'),
    },
  },

  {
    files: ['sst.config.ts'],
    rules: {
      '@typescript-eslint/triple-slash-reference': 'off',
      '@nx/enforce-module-boundaries': 'off',
    },
  },

  {
    ignores: ['apps/**', 'libs/**', 'infra/**', 'docker/**', 'tools/**'],
  },
];
