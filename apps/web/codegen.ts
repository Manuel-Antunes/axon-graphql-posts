import type { CodegenConfig } from '@graphql-codegen/cli';

/**
 * O codegen, e as DUAS saídas que ele produz — que resolvem dois problemas diferentes.
 *
 * <h2>1. `src/gql/` — o `client-preset`, que é o que torna o padrão de fragmentos possível</h2>
 * O preset gera uma função `graphql()` que devolve um `TypedDocumentNode`: o Apollo lê o tipo do
 * resultado e das variáveis DO PRÓPRIO DOCUMENTO, sem uma linha de tipo escrita à mão.
 *
 * E gera o <b>fragment masking</b>, que é a peça central desta aplicação. Um componente declara o
 * fragmento de que precisa e recebe `FragmentType<typeof Fragmento>` como prop — um tipo OPACO. Para
 * ler os campos ele chama `getFragmentData(Fragmento, prop)`. A consequência é que o TypeScript
 * RECUSA um componente ler um campo que o fragmento dele não pediu, mesmo que a query da página o
 * tenha trazido. É o over-fetching virando erro de compilação em vez de observação em code review.
 *
 * `unmaskFunctionName` é `getFragmentData` e não o default `useFragment` de propósito: o Apollo 4 tem
 * um hook `useFragment` com outro significado (ler um fragmento do cache), e duas coisas com o mesmo
 * nome num import seriam confusão permanente. Esta não é um hook — é uma função pura.
 *
 * <h2>2. `src/gql/possible-types.ts` — o que o cache PRECISA para tipos polimórficos</h2>
 * O `InMemoryCache` faz o casamento de fragmentos heuristicamente: sem ajuda, ele assume que
 * `... on Author` casa com qualquer objeto. Neste schema há uma INTERFACE (`User`, com `Author` e
 * `Reader`) e uma UNIÃO (`_Entity`) — e `me` devolve a interface. Sem `possibleTypes`, ler
 * `me { ... on Author { bio } }` do cache devolve o fragmento aplicado a um `Reader`, em silêncio.
 *
 * O plugin `fragment-matcher` lê o schema e escreve exatamente o mapa que o cache espera. Gerado, e
 * não escrito à mão, porque um tipo novo que implemente `User` tem de aparecer aqui sem ninguém
 * lembrar — ver `lib/apollo/cache.ts`.
 */
const config: CodegenConfig = {
  // O SDL versionado, não a URL: `pnpm codegen` não deve exigir a API de pé. Ver scripts/pull-schema.sh.
  schema: 'schema.graphql',
  // Os documentos moram JUNTO dos componentes que os consomem — é o que "fragment-based props"
  // quer dizer. Não há pasta de queries.
  documents: ['src/**/*.{ts,tsx}', '!src/gql/**/*'],
  ignoreNoDocuments: true,
  generates: {
    'src/gql/': {
      preset: 'client',
      presetConfig: {
        fragmentMasking: { unmaskFunctionName: 'getFragmentData' },
      },
      config: {
        // O `DateTime` do SmallRye chega como string ISO-8601 no fio. Tipá-lo como `any`
        // (o default para escalar desconhecido) apagaria o erro de passar um número.
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
