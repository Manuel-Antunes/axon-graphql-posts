'use client';

import { FileTextIcon, Loader2Icon } from 'lucide-react';

import type { FragmentType } from '@/gql';
import { EmptyState } from '@/app/_components/empty-state';
import { PostCard } from '@/app/_components/post-card';
import { Button } from '@/components/ui/button';
import { getFragmentData, graphql } from '@/gql';

export const PostList_connection = graphql(`
  fragment PostList_connection on PostConnection {
    pageInfo {
      hasNextPage
      endCursor
    }
    edges {
      cursor
      node {
        ...PostCard_post
      }
    }
  }
`);

export function PostList({
  connection,
  onLoadMore,
  loadingMore = false,
  emptyTitle = 'Nenhum post ainda',
  emptyDescription,
}: {
  connection: FragmentType<typeof PostList_connection>;
  onLoadMore?: (after: string) => void;
  loadingMore?: boolean;
  emptyTitle?: string;
  emptyDescription?: string;
}) {
  const data = getFragmentData(PostList_connection, connection);
  const edges = data.edges.filter((edge) => edge !== null);

  if (edges.length === 0) {
    return (
      <EmptyState
        icon={FileTextIcon}
        title={emptyTitle}
        description={emptyDescription}
      />
    );
  }

  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-2">
        {edges.map((edge) => (
          <PostCard key={edge.cursor} post={edge.node} />
        ))}
      </div>

      {data.pageInfo.hasNextPage && onLoadMore ? (
        <div className="flex justify-center">
          <Button
            variant="outline"
            disabled={loadingMore}
            onClick={() =>
              data.pageInfo.endCursor && onLoadMore(data.pageInfo.endCursor)
            }
          >
            {loadingMore ? <Loader2Icon className="animate-spin" /> : null}
            Carregar mais
          </Button>
        </div>
      ) : null}
    </div>
  );
}
