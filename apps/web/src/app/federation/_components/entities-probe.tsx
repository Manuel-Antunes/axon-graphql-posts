'use client';

import { useState } from 'react';
import { useQuery, useSuspenseQuery } from '@apollo/client/react';
import { Loader2Icon, NetworkIcon, PlayIcon } from 'lucide-react';

import { ErrorNotice } from '@/app/_components/error-notice';
import { useSession } from '@/app/_providers/session-provider';
import { Badge } from '@/components/ui/badge';
import { Button } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import { graphql } from '@/gql';

import { FederationSeedQuery } from '../query';

const EntitiesQuery = graphql(`
  query Entities($representations: [_Any!]!) {
    _entities(representations: $representations) {
      __typename
      ... on Post {
        id
        title
        version
      }
      ... on Tag {
        id
        name
      }
      ... on Author {
        id
        name
        email
      }
      ... on Reader {
        id
        name
      }
    }
  }
`);

// eslint-disable-next-line @typescript-eslint/consistent-type-definitions
type Representation = { __typename: string; id: string };

export function EntitiesProbe() {
  const { session } = useSession();
  const seed = useSuspenseQuery(FederationSeedQuery, { errorPolicy: 'all' });
  const [representations, setRepresentations] = useState<
    Representation[] | null
  >(null);

  const entities = useQuery(EntitiesQuery, {
    variables: {
      representations: (representations ?? []) as Record<string, unknown>[],
    },
    skip: !representations,
    fetchPolicy: 'network-only',
  });

  const nodes = (seed.data?.posts.edges ?? [])
    .filter((edge) => edge !== null)
    .map((edge) => edge.node);

  const build = (): Representation[] => {
    const list: Representation[] = [];
    for (const post of nodes) {
      list.push({ __typename: 'Post', id: post.id });
      list.push({ __typename: 'Author', id: post.author.id });
      for (const edge of post.tags.edges) {
        if (edge) list.push({ __typename: 'Tag', id: edge.node.id });
      }
    }
    list.push({
      __typename: 'Post',
      id: '00000000-0000-0000-0000-000000000000',
    });
    return list;
  };

  return (
    <div className="space-y-5">
      <Card>
        <CardHeader>
          <CardTitle className="flex items-center gap-2 text-base">
            <NetworkIcon className="size-4" aria-hidden />
            _entities(representations:)
          </CardTitle>
          <CardDescription>
            {session
              ? 'Você está autenticado, mas esta query não usa o token — ela é pública.'
              : 'Sem token. É assim que o roteador da federação chama o subgraph.'}
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {seed.error ? (
            <ErrorNotice
              title="Não foi possível ler os ids"
              error={seed.error}
            />
          ) : null}

          <Button
            onClick={() => setRepresentations(build())}
            disabled={nodes.length === 0 || entities.loading}
          >
            {entities.loading ? (
              <Loader2Icon className="animate-spin" />
            ) : (
              <PlayIcon />
            )}
            Resolver {nodes.length > 0 ? build().length : 0} representações
          </Button>

          {representations ? (
            <div className="grid gap-3 md:grid-cols-2">
              <div>
                <p className="mb-1 text-xs font-medium">Enviado</p>
                <pre className="max-h-72 overflow-auto rounded-md border bg-muted/40 p-3 font-mono text-[11px]">
                  {JSON.stringify(representations, null, 2)}
                </pre>
              </div>
              <div>
                <p className="mb-1 text-xs font-medium">Recebido</p>
                {entities.error ? (
                  <ErrorNotice
                    title="_entities falhou"
                    error={entities.error}
                  />
                ) : (
                  <pre className="max-h-72 overflow-auto rounded-md border bg-muted/40 p-3 font-mono text-[11px]">
                    {JSON.stringify(entities.data?._entities ?? [], null, 2)}
                  </pre>
                )}
              </div>
            </div>
          ) : null}

          {entities.data ? (
            <div className="flex flex-wrap gap-2">
              {entities.data._entities.map((entity, index) => (
                <Badge
                  key={index}
                  variant={entity ? 'secondary' : 'outline'}
                  className="font-mono text-[10px]"
                >
                  {index}: {entity?.__typename ?? 'null'}
                </Badge>
              ))}
            </div>
          ) : null}
        </CardContent>
      </Card>
    </div>
  );
}
