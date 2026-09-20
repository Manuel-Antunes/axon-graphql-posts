'use client';

import Link from 'next/link';
import { skipToken, useSuspenseQuery } from '@apollo/client/react';
import { ShieldCheckIcon, UserIcon } from 'lucide-react';

import type { FragmentType } from '@/gql';
import { ErrorNotice } from '@/app/_components/error-notice';
import { PostList } from '@/app/_components/post-list';
import { RelativeTime } from '@/app/_components/relative-time';
import { useSession } from '@/app/_providers/session-provider';
import { Alert, AlertDescription, AlertTitle } from '@/components/ui/alert';
import { Badge } from '@/components/ui/badge';
import { buttonVariants } from '@/components/ui/button';
import {
  Card,
  CardContent,
  CardDescription,
  CardHeader,
  CardTitle,
} from '@/components/ui/card';
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from '@/components/ui/table';
import { getFragmentData, graphql } from '@/gql';
import { cn } from '@/lib/utils';

import { MeQuery } from '../query';

/**
 * O fragmento que exige `possibleTypes` no cache — e a razão de ele existir no `codegen.ts`.
 *
 * `me` devolve a INTERFACE `User`. `Author` e `Reader` a implementam, e só o `Author` tem `bio` e
 * `posts`. Sem o mapa de tipos possíveis, o `InMemoryCache` faria o casamento de `... on Author`
 * heuristicamente: ele veria um objeto com `__typename: "Reader"` e, não sabendo que `Reader` NÃO é
 * `Author`, aplicaria o fragmento assim mesmo — em silêncio.
 *
 * <h2>E por que a lista de contas é o campo mais interessante desta página</h2>
 * Porque ela é a prova do <i>account linking</i>. Identidade fica em `users`, credencial em
 * `accounts`, uma linha por provedor. A mesma pessoa que entrou pelo Keycloak e depois pelo Cognito
 * tem UM usuário e DUAS contas — foi isso que a migração para o Cognito cobrou, e o que a
 * `V6__cognito_provider.sql` resolveu.
 */
export const IdentityPanel_user = graphql(`
  fragment IdentityPanel_user on User {
    __typename
    id
    name
    email
    accounts {
      provider
      subject
      linkedAt
      hasPassword
    }
    ... on Author {
      bio
      posts(first: 6) {
        ...PostList_connection
      }
    }
  }
`);

export function IdentityPanel() {
  const { session } = useSession();
  /*
   * `skipToken` e não `{ skip: true }`: numa query que suspende, "pular" precisa ser expresso no
   * TIPO — com `skipToken` o TypeScript sabe que `data` pode não existir, e o hook não suspende
   * esperando um resultado que nunca virá. Deslogado, a página mostra o aviso abaixo sem tocar a
   * rede.
   */
  const { data, error } = useSuspenseQuery(
    MeQuery,
    session ? { errorPolicy: 'all' as const } : skipToken,
  );

  if (!session) {
    return (
      <Alert>
        <UserIcon />
        <AlertTitle>me exige token</AlertTitle>
        <AlertDescription>
          A autenticação não é proativa nesta API:{' '}
          <span className="font-mono">posts</span> é pública no mesmo POST em
          que <span className="font-mono">me</span> exige bearer.{' '}
          <Link href="/login?next=/me" className="underline">
            Entrar
          </Link>
        </AlertDescription>
      </Alert>
    );
  }

  if (error) return <ErrorNotice title="me falhou" error={error} />;
  if (!data?.me) return null;

  return <IdentityCard user={data.me} />;
}

function IdentityCard({
  user,
}: {
  user: FragmentType<typeof IdentityPanel_user>;
}) {
  const me = getFragmentData(IdentityPanel_user, user);
  const isAuthor = me.__typename === 'Author';

  return (
    <div className="space-y-5">
      <Card>
        <CardHeader>
          <div className="flex items-center gap-2">
            <CardTitle className="text-base">{me.name}</CardTitle>
            <Badge
              variant={isAuthor ? 'default' : 'secondary'}
              className="font-mono text-[10px]"
            >
              {me.__typename}
            </Badge>
          </div>
          <CardDescription>
            {me.email} · <span className="font-mono text-[11px]">{me.id}</span>
          </CardDescription>
        </CardHeader>
        <CardContent className="space-y-4">
          {isAuthor && me.bio ? (
            <p className="text-sm">
              <span className="text-muted-foreground">bio: </span>
              {me.bio}
            </p>
          ) : null}

          <div>
            <p className="mb-2 flex items-center gap-2 text-sm font-medium">
              <ShieldCheckIcon className="size-4" aria-hidden />
              Credenciais ({me.accounts.length})
            </p>
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>Provedor</TableHead>
                  <TableHead>Subject</TableHead>
                  <TableHead>Ligada em</TableHead>
                  <TableHead>Senha</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {me.accounts
                  .filter((account) => account !== null)
                  .map((account) => (
                    <TableRow key={`${account.provider}-${account.subject}`}>
                      <TableCell>
                        <Badge
                          variant="outline"
                          className="font-mono text-[10px]"
                        >
                          {account.provider}
                        </Badge>
                      </TableCell>
                      <TableCell className="max-w-[16ch] truncate font-mono text-[11px]">
                        {account.subject}
                      </TableCell>
                      <TableCell className="text-xs">
                        <RelativeTime iso={account.linkedAt} />
                      </TableCell>
                      <TableCell className="text-xs">
                        {account.hasPassword ? 'sim' : 'não'}
                      </TableCell>
                    </TableRow>
                  ))}
              </TableBody>
            </Table>
            <p className="mt-2 text-[11px] text-muted-foreground">
              Duas linhas aqui com o mesmo e-mail significam que a mesma pessoa
              atravessou a troca de emissor sem virar dois usuários.
            </p>
          </div>
        </CardContent>
      </Card>

      {isAuthor ? (
        <section className="space-y-3">
          <h2 className="text-lg font-semibold tracking-tight">Meus posts</h2>
          <PostList
            connection={me.posts}
            emptyTitle="Este autor ainda não publicou"
            emptyDescription="Author.posts é resolvido em lote: N autores custam uma consulta."
          />
          <Link
            href="/posts/new"
            className={cn(buttonVariants({ variant: 'outline', size: 'sm' }))}
          >
            Escrever um post
          </Link>
        </section>
      ) : null}
    </div>
  );
}
