import { graphql } from '@/gql';

/**
 * A query DESTA página, num arquivo só — e é daqui que os dois lados a leem.
 *
 * <ul>
 *   <li>o <b>server component</b> (`page.tsx`) a passa ao `PreloadQuery`, que a executa no servidor e
 *       transporta o resultado pelo stream do React;</li>
 *   <li>o <b>componente de cliente</b> (`_hooks/use-post-feed.ts`) a lê com `useSuspenseQuery` — o
 *       MESMO documento e as MESMAS variáveis, que é o que faz o dado ser encontrado no cache em vez
 *       de pedido de novo.</li>
 * </ul>
 *
 * O documento não mora no hook porque ele não é do hook: é da página. Um `query.ts` ao lado do
 * `page.tsx` torna isso uma afirmação de organização, e não um acordo tácito.
 *
 * <h2>E note o que a query NÃO enumera</h2>
 * Ela pede `...PostList_connection` e mais nada. Os campos concretos (`title`, `version`, o autor, as
 * tags) estão declarados nos COMPONENTES que os desenham, e sobem por composição de fragmentos até
 * aqui. Acrescentar um campo a um card é editar um arquivo — o do card —, e não dois.
 */
export const FeedPostsQuery = graphql(`
  query FeedPosts($first: Int!, $after: String) {
    posts(first: $first, after: $after) {
      ...PostList_connection
    }
  }
`);

/**
 * O tamanho da página é constante porque o prefetch e a leitura precisam das MESMAS variáveis: se o
 * servidor pedisse 6 e o cliente 10, o `useSuspenseQuery` não acharia nada no cache e a requisição
 * sairia duas vezes — uma falha silenciosa, que só aparece na aba de rede.
 */
export const FEED_PAGE_SIZE = 6;
