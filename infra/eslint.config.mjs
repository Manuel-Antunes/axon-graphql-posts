import baseConfig from '../eslint.base.config.mjs';

export default [
  ...baseConfig,

  {
    files: ['**/*.ts'],
    rules: {
      '@typescript-eslint/triple-slash-reference': 'off',

      '@typescript-eslint/no-empty-object-type': [
        'error',
        { allowInterfaces: 'with-single-extends' },
      ],

      '@typescript-eslint/no-empty-interface': 'off',
    },
  },

  {
    ignores: ['dist/**'],
  },
];
