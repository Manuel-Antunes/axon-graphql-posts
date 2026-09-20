import { Suspense } from 'react';

import { Skeleton } from '@/components/ui/skeleton';
import { PreloadQuery } from '@/lib/apollo/rsc';

import { LiveConsole } from './_components/live-console';
import { LIVE_SNAPSHOT_SIZE, RecentPostsQuery } from './query';

export default function LivePage() {
  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Tempo real</h1>
        <p className="text-sm text-muted-foreground">
          O que atravessa, o que não atravessa, e por quê.
        </p>
      </div>
      <PreloadQuery
        query={RecentPostsQuery}
        variables={{ first: LIVE_SNAPSHOT_SIZE }}
        errorPolicy="all"
      >
        <Suspense fallback={<Skeleton className="h-96 w-full" />}>
          <LiveConsole />
        </Suspense>
      </PreloadQuery>
    </div>
  );
}
