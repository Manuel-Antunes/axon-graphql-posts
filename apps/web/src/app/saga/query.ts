import { graphql } from '@/gql';

/**
 * Os documentos desta página — e nenhum deles é prefetchado. É a única página onde isso acontece, e a
 * razão é o que ela mede.
 *
 * <h2>Por que não há `PreloadQuery` aqui</h2>
 * Porque o post que a sonda observa <b>ainda não existe</b> quando a página renderiza: ele é criado
 * pelo botão. Não há variável para prefetchar. E, mais importante, prefetchar uma MEDIÇÃO a
 * falsificaria: o que esta página cronometra é o tempo entre a mutation responder e o outro serviço
 * fechar a saga, contado a partir do clique.
 *
 * Os documentos continuam aqui, ao lado da página, pela mesma razão das outras: é o lugar onde se
 * procura o que uma página pede.
 */
export const CreateSagaPostMutation = graphql(`
  mutation CreateSagaPost($input: CreatePostInput!) {
    createPost(input: $input) {
      id
      title
      version
      createdAt
    }
  }
`);

/**
 * A sonda. Ela NÃO usa fragmento de componente de propósito: não desenha nada, MEDE. Os campos aqui
 * são os que respondem à pergunta "a saga fechou?" — a versão e a lista de tags. Amarrá-la a
 * `PostCard_post` faria a medição mudar quando alguém mexesse no card.
 */
export const SagaProbeQuery = graphql(`
  query SagaProbe($id: ID!) {
    post(id: $id) {
      id
      title
      version
      updatedAt
      tags(first: 5) {
        edges {
          node {
            id
            name
          }
        }
      }
    }
  }
`);
