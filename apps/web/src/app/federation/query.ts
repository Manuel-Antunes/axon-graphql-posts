import { graphql } from '@/gql';

/**
 * A query desta página — a que dá os ids com que a sonda é montada.
 *
 * Ela existe porque uma representação com id inventado responde `null` e não prova nada: os ids têm
 * de vir do próprio grafo. Prefetch no servidor porque é dado de PÁGINA (a sonda só fica clicável
 * depois que eles chegam), e não medição.
 */
export const FederationSeedQuery = graphql(`
  query FederationSeed {
    posts(first: 3) {
      edges {
        node {
          id
          title
          author {
            id
            name
          }
          tags(first: 2) {
            edges {
              node {
                id
                name
              }
            }
          }
        }
      }
    }
  }
`);
