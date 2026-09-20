'use client';

import { startTransition, useCallback, useState } from 'react';
import { useSuspenseQuery } from '@apollo/client/react';

import { FEED_PAGE_SIZE, FeedPostsQuery } from '../query';

export function usePostFeed() {
  const [loadingMore, setLoadingMore] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  const { data, error, fetchMore, refetch } = useSuspenseQuery(FeedPostsQuery, {
    variables: { first: FEED_PAGE_SIZE },
    errorPolicy: 'all',
  });

  const loadMore = useCallback(
    async (after: string) => {
      setLoadingMore(true);
      try {
        await fetchMore({ variables: { after } });
      } finally {
        setLoadingMore(false);
      }
    },
    [fetchMore],
  );

  const refresh = useCallback(() => {
    setRefreshing(true);
    startTransition(() => {
      void refetch().finally(() => setRefreshing(false));
    });
  }, [refetch]);

  return {
    connection: data?.posts,
    error,
    loadingMore,
    refreshing,
    loadMore,
    refresh,
  };
}
