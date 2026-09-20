'use client';

import { useSuspenseQuery } from '@apollo/client/react';

import { LIVE_SNAPSHOT_SIZE, LIVE_WINDOW, RecentPostsQuery } from '../query';

export function useRecentPosts() {
  const { data, error } = useSuspenseQuery(RecentPostsQuery, {
    variables: { first: LIVE_SNAPSHOT_SIZE },
    errorPolicy: 'all',
  });

  const all = (data?.posts.edges ?? [])
    .filter((edge) => edge !== null)
    .map((edge) => edge.node);
  const posts = all.slice(-LIVE_WINDOW).reverse();
  return { posts, error };
}
