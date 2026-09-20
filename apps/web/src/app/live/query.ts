import { graphql } from '@/gql';

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

export const LIVE_SNAPSHOT_SIZE = 100;

export const LIVE_WINDOW = 12;
