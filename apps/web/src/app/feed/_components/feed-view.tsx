'use client';

import { RefreshCwIcon } from 'lucide-react';

import { ErrorNotice } from '@/app/_components/error-notice';
import { PostList } from '@/app/_components/post-list';
import { Button } from '@/components/ui/button';

import { usePostFeed } from '../_hooks/use-post-feed';

export function FeedView() {
  const { connection, error, loadingMore, refreshing, loadMore, refresh } =
    usePostFeed();

  return (
    <div className="space-y-5">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-2xl font-semibold tracking-tight">Feed</h1>
          <p className="text-sm text-muted-foreground">
            Buscado no servidor pelo{' '}
            <span className="font-mono">PreloadQuery</span> e paginado no
            cliente pelo cache. Os cards não carregam o corpo dos posts.
          </p>
        </div>
        <Button
          variant="outline"
          size="sm"
          onClick={refresh}
          disabled={refreshing}
        >
          <RefreshCwIcon className={refreshing ? 'animate-spin' : undefined} />
          Recarregar
        </Button>
      </div>

      {error ? (
        <ErrorNotice title="Não foi possível listar os posts" error={error} />
      ) : null}

      {connection ? (
        <PostList
          connection={connection}
          onLoadMore={(after) => void loadMore(after)}
          loadingMore={loadingMore}
          emptyTitle="Nenhum post publicado"
          emptyDescription="Crie um em Escrever — ele nasce na versão 1 e o serviço de tagueamento o completa."
        />
      ) : null}
    </div>
  );
}
