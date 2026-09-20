import { Suspense } from 'react';

import { Skeleton } from '@/components/ui/skeleton';
import { PreloadQuery } from '@/lib/apollo/rsc';
import { readSession } from '@/lib/auth/cookies';

import { IdentityPanel } from './_components/identity-panel';
import { MeQuery } from './query';

export default async function MePage() {
  const session = await readSession();

  const panel = (
    <Suspense fallback={<Skeleton className="h-64 w-full" />}>
      <IdentityPanel />
    </Suspense>
  );

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-2xl font-semibold tracking-tight">Identidade</h1>
        <p className="text-sm text-muted-foreground">
          Nenhum usuário é cadastrado nesta aplicação: o perfil nasce na
          primeira requisição com um token novo, ou se liga a um existente pelo
          e-mail.
        </p>
      </div>
      {session ? (
        <PreloadQuery query={MeQuery} errorPolicy="all">
          {panel}
        </PreloadQuery>
      ) : (
        panel
      )}
    </div>
  );
}
