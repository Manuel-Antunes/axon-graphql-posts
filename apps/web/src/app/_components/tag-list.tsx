import type { FragmentType } from '@/gql';
import { TagChip } from '@/app/_components/tag-chip';
import { getFragmentData, graphql } from '@/gql';

export const TagList_post = graphql(`
  fragment TagList_post on Post {
    id
    tags(first: 10) {
      edges {
        cursor
        node {
          ...TagChip_tag
        }
      }
    }
  }
`);

export function TagList({ post }: { post: FragmentType<typeof TagList_post> }) {
  const { tags } = getFragmentData(TagList_post, post);
  const edges = tags.edges.filter((edge) => edge !== null);

  if (edges.length === 0) {
    return (
      <span className="text-xs text-muted-foreground italic">
        sem tag — o post ainda está na versão 1
      </span>
    );
  }

  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {edges.map((edge) => (
        <TagChip key={edge.cursor} tag={edge.node} />
      ))}
    </div>
  );
}
