import type { FragmentType } from '@/gql';
import { AuthorByline } from '@/app/_components/author-byline';
import { RelativeTime } from '@/app/_components/relative-time';
import { TagList } from '@/app/_components/tag-list';
import { VersionBadge } from '@/app/_components/version-badge';
import { Separator } from '@/components/ui/separator';
import { getFragmentData, graphql } from '@/gql';

export const PostArticle_post = graphql(`
  fragment PostArticle_post on Post {
    id
    title
    content
    version
    createdAt
    updatedAt
    author {
      ...AuthorByline_author
    }
    ...TagList_post
  }
`);

export function PostArticle({
  post,
}: {
  post: FragmentType<typeof PostArticle_post>;
}) {
  const data = getFragmentData(PostArticle_post, post);

  return (
    <article className="space-y-5">
      <header className="space-y-3">
        <div className="flex flex-wrap items-center gap-3">
          <VersionBadge version={data.version} />
          <span className="font-mono text-xs text-muted-foreground">
            {data.id}
          </span>
        </div>
        <h1 className="text-2xl font-semibold tracking-tight text-balance">
          {data.title}
        </h1>
        <div className="flex flex-wrap items-center justify-between gap-3">
          <AuthorByline author={data.author} />
          <div className="text-xs text-muted-foreground">
            criado <RelativeTime iso={data.createdAt} /> · atualizado{' '}
            <RelativeTime iso={data.updatedAt} />
          </div>
        </div>
        <TagList post={data} />
      </header>

      <Separator />

      <div className="text-sm leading-relaxed whitespace-pre-wrap">
        {data.content}
      </div>
    </article>
  );
}
