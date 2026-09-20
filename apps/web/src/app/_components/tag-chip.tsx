import type { FragmentType } from '@/gql';
import { Badge } from '@/components/ui/badge';
import { getFragmentData, graphql } from '@/gql';

export const TagChip_tag = graphql(`
  fragment TagChip_tag on Tag {
    id
    name
  }
`);

export function TagChip({ tag }: { tag: FragmentType<typeof TagChip_tag> }) {
  const { name } = getFragmentData(TagChip_tag, tag);
  return (
    <Badge variant="secondary" className="font-mono text-[11px]">
      #{name}
    </Badge>
  );
}
