import { dirname } from 'path';
import { fileURLToPath } from 'url';
import { FlatCompat } from '@eslint/eslintrc';
import {
  configs as graphqlConfigs,
  parser as graphqlParser,
  processors as graphqlProcessors,
  rules as graphqlRules,
} from '@graphql-eslint/eslint-plugin';
import vitest from '@vitest/eslint-plugin';
import tailwind from 'eslint-plugin-better-tailwindcss';

import baseConfig from '../../eslint.base.config.mjs';

const __filename = fileURLToPath(import.meta.url);
const __dirname = dirname(__filename);

const compat = new FlatCompat({
  baseDirectory: __dirname,
});

const eslintConfig = [
  ...baseConfig,
  ...compat.extends('next/core-web-vitals', 'next/typescript'),
  {
    ignores: [
      'node_modules/**',
      '.next/**',
      'out/**',
      'build/**',
      'next-env.d.ts',
      'src/gql/**',
      '.open-next/**',
    ],
  },
  {
    files: ['src/**/*.spec.{ts,tsx}'],
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
    files: ['src/**/*.{ts,tsx}'],
    plugins: { 'better-tailwindcss': tailwind },
    settings: {
      'better-tailwindcss': { entryPoint: 'src/app/globals.css' },
    },
    rules: {
      ...tailwind.configs['correctness-error'].rules,
      'better-tailwindcss/no-deprecated-classes': 'error',
      'better-tailwindcss/no-duplicate-classes': 'error',
      'better-tailwindcss/no-unnecessary-whitespace': 'error',
      'better-tailwindcss/no-unknown-classes': [
        'error',
        { ignore: ['toaster'] },
      ],
    },
  },

  {
    files: ['src/**/*.{ts,tsx}'],
    ignores: ['src/gql/**'],
    processor: graphqlProcessors.graphql,
  },

  {
    files: ['**/*.graphql'],
    ignores: ['schema.graphql'],
    languageOptions: { parser: graphqlParser },
    plugins: { '@graphql-eslint': { rules: graphqlRules } },
    rules: {
      ...graphqlConfigs['flat/operations-recommended'].rules,
      '@graphql-eslint/naming-convention': [
        'error',
        {
          ...graphqlConfigs['flat/operations-recommended'].rules[
            '@graphql-eslint/naming-convention'
          ][1],
          FragmentDefinition: {
            requiredPattern: /^[A-Z][A-Za-z0-9]*_[a-z][A-Za-z0-9]*$/,
          },
          allowLeadingUnderscore: true,
        },
      ],
    },
  },
];

export default eslintConfig;
