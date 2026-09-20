'use client';

import { useSuspenseQuery } from '@apollo/client/react';

import { LIVE_SNAPSHOT_SIZE, LIVE_WINDOW, RecentPostsQuery } from '../query';

/**
 * O estado ATUAL, prefetchado no servidor — a referência contra a qual os eventos são lidos.
 *
 * Sem ele a página só mostraria o que mudou desde que alguém a abriu, e "nada aconteceu ainda" seria
 * indistinguível de "não estou recebendo nada". Com ele há sempre o que comparar.
 *
 * Não há intervalo aqui: quem avisa de mudança é o stream. Esta é uma leitura só.
 */
export function useRecentPosts() {
  const { data, error } = useSuspenseQuery(RecentPostsQuery, {
    variables: { first: LIVE_SNAPSHOT_SIZE },
    errorPolicy: 'all',
  });

  // A CAUDA, invertida: `posts` vem em ordem de criação crescente (ver `query.ts`), e o que
  // interessa a um painel de tempo real é o fim da lista, com o mais novo em cima.
  const all = (data?.posts.edges ?? [])
    .filter((edge) => edge !== null)
    .map((edge) => edge.node);
  const posts = all.slice(-LIVE_WINDOW).reverse();
  return { posts, error };
}
