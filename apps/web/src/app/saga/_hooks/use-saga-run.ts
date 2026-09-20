'use client';

import { useCallback, useEffect, useRef, useState } from 'react';
import { useApolloClient } from '@apollo/client/react';

import { CreateSagaPostMutation, SagaProbeQuery } from '../query';

export interface SagaEvent {
  /** Milissegundos desde o início da corrida. É a unidade que interessa aqui. */
  at: number;
  label: string;
  detail: string;
  tone: 'neutral' | 'pending' | 'good' | 'bad';
}

export type SagaStatus =
  'idle' | 'creating' | 'waiting' | 'closed' | 'timeout' | 'error';

const POLL_INTERVAL_MS = 1_000;
const TIMEOUT_MS = 180_000;

/**
 * Cria um post e espera a versão 2.
 *
 * <h2>Por que polling, e não subscription</h2>
 * Porque o que esta página mede é o TEMPO da travessia, e uma subscription responde outra pergunta:
 * ela diz que o evento chegou, não quantos segundos ele levou desde o `createPost`. O polling é o
 * relógio — cada tentativa é uma marca na régua.
 * <p>
 * Houve uma segunda razão, e ela deixou de valer: contra a stack em Lambda a subscription não
 * entregava nada, porque `emitUpdate` é em processo e a mutation cai noutro container. Hoje entrega —
 * o pacote dos handlers que notificam roda num processor streaming, que lê o event store. Quem mostra
 * isso é a página `/live`.
 *
 * <h2>Os ~50 segundos</h2>
 * Numa execução fria o tempo é quase todo cold start: duas JVMs de 72 MB subindo dentro de um VPC.
 * Repetir a corrida com as funções quentes é o que separa o custo do transporte do custo do Java —
 * e por isso o botão pode ser apertado várias vezes.
 */
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

        // `network-only`: o cache tem a v1, e é justamente a mudança que se quer ver.
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

  /** Observa um post que já existe — o link "Observar a saga" de /posts/new cai aqui. */
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
