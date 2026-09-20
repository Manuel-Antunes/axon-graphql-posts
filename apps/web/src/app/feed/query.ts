import { graphql } from '@/gql';

export const FeedPostsQuery = graphql(`
  query FeedPosts($first: Int!, $after: String) {
    posts(first: $first, after: $after) {
      ...PostList_connection
    }
  }
`);

export const FEED_PAGE_SIZE = 6;
