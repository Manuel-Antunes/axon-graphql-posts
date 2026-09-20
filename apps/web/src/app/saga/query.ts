import { graphql } from '@/gql';

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
