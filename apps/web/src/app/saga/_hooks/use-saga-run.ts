'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useApolloClient } from '@apollo/client/react';

import { CreateSagaPostMutation, SagaProbeQuery } from '../query';

export interface SagaEvent {
  at: number;
  label: string;
  detail: string;
  tone: 'neutral' | 'pending' | 'good' | 'bad';
}

export type SagaStatus =
  'idle' | 'creating' | 'waiting' | 'closed' | 'timeout' | 'error';

const POLL_INTERVAL_MS = 1_000;
const TIMEOUT_MS = 180_000;

export function useSagaRun() {
  const client = useApolloClient();
  const [status, setStatus] = useState<SagaStatus>('idle');
  const [events, setEvents] = useState<SagaEvent[]>([]);
  const [postId, setPostId] = useState<string | null>(null);
  const [elapsed, setElapsed] = useState(0);
  const startedAt = useRef<number>(0);
  const cancelled = useRef(false);

  useEffect(() => () => void (cancelled.current = true), []);

  const push = useCallback((event: Omit<SagaEvent, 'at'>) => {
    setEvents((current) => [
      ...current,
      { ...event, at: Date.now() - startedAt.current },
    ]);
  }, []);

  const observe = useCallback(
    async (id: string) => {
      setStatus('waiting');
      while (
        !cancelled.current &&
        Date.now() - startedAt.current < TIMEOUT_MS
      ) {
        await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
        if (cancelled.current) return;
        setElapsed(Date.now() - startedAt.current);

        const { data } = await client.query({
          query: SagaProbeQuery,
          variables: { id },
          fetchPolicy: 'network-only',
        });

        const post = data?.post;
        if (!post) continue;

        if (post.version >= 2) {
          const tags = post.tags.edges
            .filter((edge) => edge !== null)
            .map((edge) => edge.node.name);
          push({
            label: `PostCreated · versão ${post.version}`,
            detail:
              tags.length > 0
                ? `o outro serviço decidiu a tag: ${tags.map((tag) => `#${tag}`).join(', ')}`
                : 'versão 2 sem tag — isto não deveria acontecer',
            tone: tags.length > 0 ? 'good' : 'bad',
          });
          setStatus('closed');
          return;
        }
      }
      if (!cancelled.current) {
        push({
          label: 'Tempo esgotado',
          detail: `${TIMEOUT_MS / 1000}s sem a versão 2. Veja as DLQ: infra/scripts/discover.sh`,
          tone: 'bad',
        });
        setStatus('timeout');
      }
    },
    [client, push],
  );

  const run = useCallback(async () => {
    cancelled.current = false;
    startedAt.current = Date.now();
    setEvents([]);
    setElapsed(0);
    setPostId(null);
    setStatus('creating');

    const stamp = new Date().toISOString().slice(11, 19);
    try {
      const { data } = await client.mutate({
        mutation: CreateSagaPostMutation,
        variables: {
          input: {
            title: `Saga ${stamp}`,
            content:
              'Post criado pela página /saga para medir a travessia entre os dois serviços.',
          },
        },
      });

      const created = data?.createPost;
      if (!created) throw new Error('createPost não devolveu o post.');

      setPostId(created.id);
      push({
        label: `PostPreCreated · versão ${created.version}`,
        detail: 'a mutation respondeu. O post existe e não está completo.',
        tone: created.version === 1 ? 'neutral' : 'bad',
      });
      push({
        label: 'SNS → SQS',
        detail:
          'posts.PostPreCreated saiu pelo outbox. O serviço de tagueamento decide agora.',
        tone: 'pending',
      });

      await observe(created.id);
    } catch (error) {
      push({
        label: 'Falhou',
        detail: error instanceof Error ? error.message : String(error),
        tone: 'bad',
      });
      setStatus('error');
    }
  }, [client, observe, push]);

  const watch = useCallback(
    async (id: string) => {
      cancelled.current = false;
      startedAt.current = Date.now();
      setEvents([]);
      setElapsed(0);
      setPostId(id);
      push({ label: 'Observando', detail: id, tone: 'pending' });
      await observe(id);
    },
    [observe, push],
  );

  const stop = useCallback(() => {
    cancelled.current = true;
    setStatus((current) =>
      current === 'waiting' || current === 'creating' ? 'idle' : current,
    );
  }, []);

  return { status, events, postId, elapsed, run, watch, stop };
}
