'use client';

import { useCallback, useEffect, useState } from 'react';
import { useSubscription } from '@apollo/client/react';

import type { StreamStatus } from '@/app/_components/status-dot';
import { onSseConnected } from '@/lib/apollo/links/sse-link';

import { OnPostCreatedSubscription, OnPostUpdatedSubscription } from '../query';

export interface StreamEvent {
  receivedAt: number;
  source: 'onPostCreated' | 'onPostUpdated';
  postId: string;
  title: string;
  version: number;
}

function statusOf(
  active: boolean,
  open: boolean,
  error: unknown,
): StreamStatus {
  if (!active) return 'idle';
  if (error) return 'error';
  return open ? 'open' : 'connecting';
}

export function usePostStream(active: boolean) {
  const [events, setEvents] = useState<StreamEvent[]>([]);
  const [open, setOpen] = useState({ created: false, updated: false });

  useEffect(() => {
    if (!active) {
      setOpen({ created: false, updated: false });
      return;
    }
    const offCreated = onSseConnected('OnPostCreated', () =>
      setOpen((current) => ({ ...current, created: true })),
    );
    const offUpdated = onSseConnected('OnPostUpdated', () =>
      setOpen((current) => ({ ...current, updated: true })),
    );
    return () => {
      offCreated();
      offUpdated();
    };
  }, [active]);

  const record = useCallback((source: StreamEvent['source'], post: unknown) => {
    const value = post as
      { id?: string; title?: string; version?: number } | null | undefined;
    const postId = value?.id;
    if (!postId) return;

    setEvents((current) => [
      {
        receivedAt: Date.now(),
        source,
        postId,
        title: value?.title ?? '(sem título)',
        version: value?.version ?? 0,
      },
      ...current,
    ]);
  }, []);

  const created = useSubscription(OnPostCreatedSubscription, {
    skip: !active,
    variables: {},
    onData: ({ data }) => record('onPostCreated', data.data?.onPostCreated),
  });

  const updated = useSubscription(OnPostUpdatedSubscription, {
    skip: !active,
    variables: {},
    onData: ({ data }) => record('onPostUpdated', data.data?.onPostUpdated),
  });

  return {
    events,
    clear: () => setEvents([]),
    createdStatus: statusOf(active, open.created, created.error),
    updatedStatus: statusOf(active, open.updated, updated.error),
    error: created.error ?? updated.error,
  };
}
