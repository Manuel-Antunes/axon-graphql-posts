import { Suspense } from 'react';

import { PreloadQuery } from '@/lib/apollo/rsc';

import { FeedSkeleton } from './_components/feed-skeleton';
import { FeedView } from './_components/feed-view';
import { FEED_PAGE_SIZE, FeedPostsQuery } from './query';

/**
 * O prefetch acontece AQUI, no servidor.
 *
 * `PreloadQuery` dispara a query com o cliente de `lib/apollo/rsc.ts` — que já leva o token do cookie
 * `httpOnly` — e transporta o resultado pelo stream do React. O `FeedView`, que é de cliente, lê o
 * MESMO documento com `useSuspenseQuery` e encontra o dado já no cache.
 *
 * `posts` é PÚBLICA na API (`quarkus.http.auth.proactive=false` é o que torna isso possível no mesmo
 * POST em que `me` exige token), então esta página funciona deslogada — e o prefetch também.
 *
 * `errorPolicy: "all"` nos dois lados: com o default, um erro do servidor viraria exceção no render
 * do RSC e a rota inteira cairia. Aqui ele vira dado, e a página o mostra.
 */
export default function FeedPage() {
  return (
    <PreloadQuery
      query={FeedPostsQuery}
      variables={{ first: FEED_PAGE_SIZE }}
      errorPolicy="all"
    >
      <Suspense fallback={<FeedSkeleton />}>
        <FeedView />
      </Suspense>
    </PreloadQuery>
  );
}
