import { graphql } from '@/gql';

export const PostByIdQuery = graphql(`
  query PostById($id: ID!) {
    post(id: $id) {
      id
      version
      ...PostArticle_post
      ...PostEditor_post
    }
  }
`);
