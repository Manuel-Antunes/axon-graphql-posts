import type { CodegenConfig } from '@graphql-codegen/cli';

const config: CodegenConfig = {
  schema: 'schema.graphql',
  documents: ['src/**/*.{ts,tsx}', '!src/gql/**/*'],
  ignoreNoDocuments: true,
  generates: {
    'src/gql/': {
      preset: 'client',
      presetConfig: {
        fragmentMasking: { unmaskFunctionName: 'getFragmentData' },
      },
      config: {
        scalars: {
          DateTime: 'string',
          BigInteger: 'string',
          BigDecimal: 'string',
          _Any: 'Record<string, unknown>',
        },
        useTypeImports: true,
        skipTypename: false,
        enumsAsTypes: true,
      },
    },
    'src/gql/possible-types.ts': {
      plugins: ['fragment-matcher'],
      config: { module: 'es2015', apolloClientVersion: 3 },
    },
  },
  hooks: {
    afterAllFileWrite: [],
  },
};

export default config;
