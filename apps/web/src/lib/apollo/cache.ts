import { InMemoryCache } from '@apollo/client-integration-nextjs';
import { relayStylePagination } from '@apollo/client/utilities';

import generatedIntrospection from '@/gql/possible-types';

/**
 * O cache, e as DUAS coisas que ele precisa saber e não consegue deduzir do resultado JSON.
 *
 * <h2>Por que o `InMemoryCache` vem da INTEGRAÇÃO, e não de `@apollo/client`</h2>
 * Porque esta aplicação tem dois lugares onde uma query roda: o React Server Component, que a executa
 * no servidor pelo `PreloadQuery`, e o componente de cliente, que a lê. A versão da integração é a
 * que sabe <b>transportar</b> o resultado de um para o outro pelo stream do React — com o cache normal
 * o dado chegaria ao navegador como HTML e a query seria refeita na hidratação.
 * <p>
 * O pacote resolve para builds diferentes conforme a condição de exportação (`react-server` vs
 * `browser`), então <b>este mesmo arquivo</b> serve os dois lados. É por isso que existe um só.
 *
 * <h2>1. `possibleTypes` — quem implementa o quê</h2>
 * Sem este mapa, o casamento de fragmentos do `InMemoryCache` é <b>heurístico</b>: ao ler
 * `... on Author` de um objeto no cache, ele não tem como saber se aquele objeto é um `Author` — a
 * resposta só traz `__typename: "Author"`, e nada diz que `Author` implementa `User`. O Apollo então
 * assume que casa. Nesta aplicação isso não é hipotético:
 *
 * <ul>
 *   <li>`me` devolve a INTERFACE `User`, com `Author` e `Reader` por baixo — e a página `/me` lê
 *       `... on Author { bio, posts }`, que um `Reader` não tem;</li>
 *   <li>`_entities` devolve a UNIÃO `_Entity` (`Author | Post | Reader | Tag`), e a página
 *       `/federation` separa os quatro por tipo.</li>
 * </ul>
 *
 * O mapa é GERADO do schema pelo plugin `fragment-matcher` (ver `codegen.ts`). Escrito à mão, ele
 * ficaria desatualizado no dia em que um tipo novo implementasse `User` — e o sintoma seria um
 * fragmento aplicado ao tipo errado, em silêncio, meses depois.
 *
 * <h2>2. `relayStylePagination` — como juntar duas páginas</h2>
 * `posts(first:, after:)` é uma cursor connection: a segunda página é um resultado DIFERENTE, com
 * outras variáveis, e o default do cache é guardá-la separada. `keyArgs: false` diz que `first` e
 * `after` NÃO identificam o campo (são só paginação), e o helper do Apollo funde `edges` e atualiza
 * `pageInfo` — que é exatamente o que "carregar mais" quer dizer.
 *
 * A forma da connection aqui bate com a que o helper espera (`edges { cursor node }` +
 * `pageInfo { hasNextPage endCursor ... }`) porque `interfaces/graphql/relay` seguiu a convenção
 * Relay à risca.
 */
export function createCache(): InMemoryCache {
  return new InMemoryCache({
    possibleTypes: generatedIntrospection.possibleTypes,
    typePolicies: {
      Query: {
        fields: {
          // `keyArgs: ["first"]`, e não o default `false`. Duas páginas do MESMO tamanho
          // continuam se fundindo (é o que "carregar mais" precisa, porque `after` fica de
          // fora da chave), mas `posts(first: 6)` do feed e `posts(first: 100)` de `/live`
          // passam a ser campos distintos no cache. Sem isso, abrir `/live` fazia o feed
          // aparecer com cem cards na volta.
          posts: relayStylePagination(['first']),
        },
      },
      // Os ids são escalares no fio (`PostId` leva `@JsonValue`), então o `id` default do
      // Apollo normaliza tudo sozinho. O que precisa de ajuda é o `Tag` DENTRO da connection do
      // post: duas listas diferentes podem trazer a mesma tag, e sem normalização ela viraria
      // dois objetos. `keyFields: ["id"]` é o default — está escrito para deixar claro que a
      // identidade é o id, e não a posição na lista.
      Post: { keyFields: ['id'] },
      Tag: { keyFields: ['id'] },
      Author: { keyFields: ['id'] },
      Reader: { keyFields: ['id'] },
    },
  });
}
