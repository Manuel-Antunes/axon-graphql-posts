import { graphql } from '@/gql';

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
