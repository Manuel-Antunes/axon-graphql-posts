"use client";

import { startTransition, useCallback, useState } from "react";
import { useSuspenseQuery } from "@apollo/client/react";

import { FEED_PAGE_SIZE, FeedPostsQuery } from "../query";

/**
 * `useSuspenseQuery` e não `useQuery`, e a razão é o prefetch.
 *
 * O `PreloadQuery` do `page.tsx` executa esta mesma query no servidor e transporta o resultado. É o
 * `useSuspenseQuery` que sabe esperar por esse transporte: ele suspende, o React mostra o `fallback`
 * do `Suspense`, e quando o dado chega o componente renderiza JÁ com ele — sem o ciclo
 * "monta vazio → dispara requisição → re-renderiza".
 *
 * `errorPolicy: "all"` porque o default (`"none"`) LANÇA, e um throw aqui subiria para o `error.tsx`
 * da rota, trocando a página inteira por uma tela de erro. Numa aplicação que existe para observar a
 * API, o erro é conteúdo: ele é mostrado no lugar da lista, com o `code` que o servidor traduziu.
 */
export function usePostFeed() {
    const [loadingMore, setLoadingMore] = useState(false);
    const [refreshing, setRefreshing] = useState(false);

    const { data, error, fetchMore, refetch } = useSuspenseQuery(FeedPostsQuery, {
        variables: { first: FEED_PAGE_SIZE },
        errorPolicy: "all",
    });

    const loadMore = useCallback(
        async (after: string) => {
            setLoadingMore(true);
            try {
                // Só `after` muda. Quem funde as duas páginas é o `relayStylePagination()` do cache
                // (ver `lib/apollo/cache.ts`) — aqui não há concatenação escrita à mão.
                await fetchMore({ variables: { after } });
            } finally {
                setLoadingMore(false);
            }
        },
        [fetchMore],
    );

    /**
     * `startTransition` não é enfeite: sem ele, `refetch` num componente que suspende desmonta a
     * árvore e o `fallback` do `Suspense` volta — a lista pisca a cada recarga. Dentro da transição o
     * React mantém o conteúdo anterior na tela até o novo chegar.
     */
    const refresh = useCallback(() => {
        setRefreshing(true);
        startTransition(() => {
            void refetch().finally(() => setRefreshing(false));
        });
    }, [refetch]);

    return { connection: data?.posts, error, loadingMore, refreshing, loadMore, refresh };
}
