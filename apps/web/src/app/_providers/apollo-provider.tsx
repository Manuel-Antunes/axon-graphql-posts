'use client';

import { ApolloNextAppProvider } from '@apollo/client-integration-nextjs';

import { makeClient } from '@/lib/apollo/client';

/**
 * O provider da integração do Next, e não o `ApolloProvider` de sempre.
 *
 * A diferença que importa: ele recebe uma FÁBRICA (`makeClient`) em vez de um cliente pronto. É o que
 * permite ao mesmo componente funcionar nos dois momentos — no render de servidor do componente de
 * cliente e depois no navegador —, e é ele quem recebe, pelo stream do React, os resultados que o
 * `PreloadQuery` executou no servidor. Sem isso, o `useSuspenseQuery` do outro lado não encontraria
 * nada no cache e refaria toda query na hidratação.
 */
export function ApolloProvider({ children }: { children: React.ReactNode }) {
  return (
    <ApolloNextAppProvider makeClient={makeClient}>
      {children}
    </ApolloNextAppProvider>
  );
}
