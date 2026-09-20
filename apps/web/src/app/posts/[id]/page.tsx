import { Suspense } from 'react';

import { Skeleton } from '@/components/ui/skeleton';
import { PreloadQuery } from '@/lib/apollo/rsc';

import { PostView } from './_components/post-view';
import { PostByIdQuery } from './query';

export default async function PostPage({
  params,
}: {
  params: Promise<{ id: string }>;
}) {
  const { id } = await params;

  return (
    <PreloadQuery query={PostByIdQuery} variables={{ id }} errorPolicy="all">
      <Suspense fallback={<Skeleton className="h-72 w-full" />}>
        <PostView id={id} />
      </Suspense>
    </PreloadQuery>
  );
}
