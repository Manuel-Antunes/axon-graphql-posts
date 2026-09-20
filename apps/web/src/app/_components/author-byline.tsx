import type { FragmentType } from '@/gql';
import { Avatar, AvatarFallback } from '@/components/ui/avatar';
import { getFragmentData, graphql } from '@/gql';

export const AuthorByline_author = graphql(`
  fragment AuthorByline_author on Author {
    id
    name
    email
  }
`);

export function AuthorByline({
  author,
  className,
}: {
  author: FragmentType<typeof AuthorByline_author>;
  className?: string;
}) {
  const { name, email } = getFragmentData(AuthorByline_author, author);
  const initials = name
    .split(/\s+/)
    .slice(0, 2)
    .map((part) => part.charAt(0).toUpperCase())
    .join('');

  return (
    <div className={className}>
      <div className="flex items-center gap-2">
        <Avatar className="size-7">
          <AvatarFallback className="text-[11px]">
            {initials || '?'}
          </AvatarFallback>
        </Avatar>
        <div className="leading-tight">
          <div className="text-sm font-medium">{name}</div>
          <div className="text-xs text-muted-foreground">{email}</div>
        </div>
      </div>
    </div>
  );
}
