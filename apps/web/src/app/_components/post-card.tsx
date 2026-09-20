import Link from 'next/link';

import type { FragmentType } from '@/gql';
import { AuthorByline } from '@/app/_components/author-byline';
import { RelativeTime } from '@/app/_components/relative-time';
import { TagList } from '@/app/_components/tag-list';
import { VersionBadge } from '@/app/_components/version-badge';
import {
  Card,
  CardContent,
  CardFooter,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { getFragmentData, graphql } from '@/gql';

export const PostCard_post = graphql(`
  fragment PostCard_post on Post {
    id
    title
    version
    createdAt
    updatedAt
    author {
      ...AuthorByline_author
    }
    ...TagList_post
  }
`);

export function PostCard({
  post,
}: {
  post: FragmentType<typeof PostCard_post>;
}) {
  const data = getFragmentData(PostCard_post, post);

  return (
    <Card className="transition-colors hover:border-foreground/20">
      <CardHeader>
        <div className="flex items-start justify-between gap-4">
          <CardTitle className="text-base leading-snug">
            <Link href={`/posts/${data.id}`} className="hover:underline">
              {data.title}
            </Link>
          </CardTitle>
          <VersionBadge version={data.version} />
        </div>
      </CardHeader>
      <CardContent className="space-y-3">
        <AuthorByline author={data.author} />
        <TagList post={data} />
      </CardContent>
      <CardFooter className="justify-between text-xs text-muted-foreground">
        <span>
          criado <RelativeTime iso={data.createdAt} />
        </span>
        <span className="font-mono opacity-60">{data.id.slice(0, 8)}</span>
      </CardFooter>
    </Card>
  );
}
