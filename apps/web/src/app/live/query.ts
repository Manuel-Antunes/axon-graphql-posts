import { graphql } from '@/gql';

/**
 * Os documentos desta página. Três, e só UM deles é prefetchável — o que diz muito sobre a página.
 *
 * <h2>As duas subscriptions saem pelo MESMO `/graphql`</h2>
 * O que muda é o cabeçalho: o `split` do cliente (ver `lib/apollo/client.ts`) manda toda operação do
 * tipo `subscription` pelo `GraphQLSSELink`, que abre um POST com `Accept: text/event-stream`. Query e
 * mutation continuam saindo pelo `HttpLink`, no mesmo endereço.
 *
 * Elas NÃO são prefetchadas, e não por esquecimento: um `PreloadQuery` executa uma operação que
 * TERMINA, e uma subscription não termina. Não existe SSE dentro de um render de servidor.
 *
 * A seleção delas (`...PostCard_post`) atravessa o proxy sem ser lida: ele repassa o corpo da
 * resposta do `posts-api` como veio. Acrescentar um campo a um card muda o que chega, sem uma linha
 * no servidor do Next.
 */
export const OnPostCreatedSubscription = graphql(`
  subscription OnPostCreated($authorId: ID) {
    onPostCreated(authorId: $authorId) {
      id
      title
      version
      ...PostCard_post
    }
  }
`);

export const OnPostUpdatedSubscription = graphql(`
  subscription OnPostUpdated($authorId: ID, $postId: ID) {
    onPostUpdated(authorId: $authorId, postId: $postId) {
      id
      title
      version
      ...PostCard_post
    }
  }
`);

/**
 * O estado atual — e ESTE é prefetchado.
 *
 * É a referência contra a qual os eventos da subscription são lidos: sem ela a página começaria
 * vazia e só teria sentido para quem ficasse olhando. Vem do servidor, junto com o HTML, em vez de
 * custar um ciclo de espera no navegador.
 */
export const RecentPostsQuery = graphql(`
  query RecentPosts($first: Int!) {
    posts(first: $first) {
      edges {
        node {
          id
          title
          version
          updatedAt
        }
      }
    }
  }
`);

/**
 * 100 — o TETO de página do servidor —, e não 10. A razão é a ordenação, e ela custa explicação.
 *
 * `Query.posts` devolve os posts em <b>ordem de criação CRESCENTE</b> ("Posts em ordem de criação",
 * diz o schema). Então `posts(first: 10)` são os DEZ MAIS ANTIGOS — e um post criado agora entra no
 * fim da lista, onde uma janela de 10 nunca o alcança. Foi exatamente assim que o painel de tempo
 * real ficou mudo enquanto posts novos apareciam: ele estava olhando para o outro lado.
 *
 * A API não oferece `last`/`before` nem um argumento de ordenação, então a saída é pedir a página
 * inteira e olhar a CAUDA dela. O limite é real e está declarado: acima de 100 posts, este painel
 * deixa de ver os mais novos. O conserto honesto é no servidor — paginação reversa —, não aqui.
 */
export const LIVE_SNAPSHOT_SIZE = 100;

/** Quantos, da cauda, a página mostra e o stream compara. */
export const LIVE_WINDOW = 12;
