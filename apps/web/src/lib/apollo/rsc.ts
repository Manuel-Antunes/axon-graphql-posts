import 'server-only';

import { HttpLink } from '@apollo/client';
import {
  ApolloClient,
  registerApolloClient,
} from '@apollo/client-integration-nextjs';

import { readSession } from '@/lib/auth/cookies';
import { GRAPHQL_UPSTREAM } from '@/lib/env';

import './fragment-warnings';

import { createCache } from './cache';

/**
 * O cliente do SERVIDOR, e o `PreloadQuery` que as páginas usam.
 *
 * <h2>O que o `registerApolloClient` resolve</h2>
 * Um `ApolloClient` por REQUISIÇÃO. A fábrica abaixo é embrulhada no `cache()` do React, então todos
 * os componentes de servidor de uma mesma requisição compartilham um cliente — e requisições
 * diferentes nunca compartilham cache, que é o vazamento clássico de SSR (o usuário A enxergando a
 * projeção do usuário B).
 *
 * <h2>Por que a fábrica é ASSÍNCRONA</h2>
 * Porque o token está num cookie `httpOnly`, e `cookies()` é assíncrono no Next 15. Ler a sessão aqui
 * — e não num link — deixa o header fixo para todas as operações daquela requisição, que é o certo:
 * dentro de um render a identidade não muda.
 *
 * <h2>O que este cliente NÃO tem</h2>
 * O link de SSE. Subscription não existe em render de servidor: não há para onde streamar, e o
 * `PreloadQuery` espera uma operação que TERMINA. Quem assina é o cliente do navegador (`client.ts`).
 *
 * <h2>A regra que isto estabelece</h2>
 * <b>Toda página com query faz o prefetch dela aqui, e o componente de cliente lê o mesmo documento
 * com `useSuspenseQuery`.</b> O documento mora num `query.ts` ao lado da página — um lugar só, usado
 * dos dois lados. O resultado atravessa pelo stream do React: o HTML já chega preenchido e a
 * hidratação não refaz a requisição.
 */
export const { getClient, query, PreloadQuery } = registerApolloClient(
  async () => {
    const session = await readSession();

    return new ApolloClient({
      cache: createCache(),
      link: new HttpLink({
        uri: GRAPHQL_UPSTREAM,
        headers: session ? { authorization: `Bearer ${session.idToken}` } : {},
        // Um render de servidor não deve ser servido do cache de `fetch` do Next: o feed mudaria
        // de conteúdo sem que ninguém invalidasse nada. O que vale aqui é o que a API responde
        // agora.
        fetchOptions: { cache: 'no-store' },
      }),
    });
  },
);
