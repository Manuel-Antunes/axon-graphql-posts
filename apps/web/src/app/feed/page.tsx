import { Suspense } from 'react';

import { PreloadQuery } from '@/lib/apollo/rsc';

import { FeedSkeleton } from './_components/feed-skeleton';
import { FeedView } from './_components/feed-view';
import { FEED_PAGE_SIZE, FeedPostsQuery } from './query';

export default function FeedPage() {
  return (
    <PreloadQuery
      query={FeedPostsQuery}
      variables={{ first: FEED_PAGE_SIZE }}
      errorPolicy="all"
    >
      <Suspense fallback={<FeedSkeleton />}>
        <FeedView />
      </Suspense>
    </PreloadQuery>
  );
}
