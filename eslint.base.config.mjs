import nx from '@nx/eslint-plugin';
import prettier from 'eslint-config-prettier/flat';

export default [
  {
    ignores: [
      '**/target/**',
      '**/dist/**',
      '**/.next/**',
      '**/.open-next/**',
      '**/out-tsc/**',
      '**/.nx/**',
      '**/.sst/**',
      '**/vite.config.*.timestamp*',
      '**/vitest.config.*.timestamp*',
      '**/sst-env.d.ts',
      '**/next-env.d.ts',
    ],
  },

  ...nx.configs['flat/base'],
  ...nx.configs['flat/typescript'],
  ...nx.configs['flat/javascript'],

  {
    files: ['**/*.ts', '**/*.tsx', '**/*.js', '**/*.jsx'],
    rules: {
      '@nx/enforce-module-boundaries': [
        'error',
        {
          enforceBuildableLibDependency: true,
          allow: ['^.*/eslint(\\.base)?\\.config\\.[cm]?[jt]s$'],
          depConstraints: [{ sourceTag: '*', onlyDependOnLibsWithTags: ['*'] }],
        },
      ],
    },
  },

  {
    files: [
      '**/*.ts',
      '**/*.tsx',
      '**/*.cts',
      '**/*.mts',
      '**/*.js',
      '**/*.jsx',
      '**/*.cjs',
      '**/*.mjs',
    ],
    rules: {
      '@typescript-eslint/consistent-type-imports': 'warn',
      '@typescript-eslint/no-namespace': 'off',
      '@typescript-eslint/consistent-type-definitions': 'error',
    },
  },

  prettier,
];
