import { beforeAll, describe, expect, it } from 'vitest';

import type { AuthenticatedApi, SseSubscription } from '../support/posts-api';
import { AxonEnvelope } from '../support/broker';
import { ChoreographyStack } from '../support/choreography-stack';
import { sleep } from '../support/posts-api';

const stack = new ChoreographyStack();

interface Post {
  id: string;
  version: number;
  tags: { edges: Array<{ node: { name: string } }> };
}

const tagsOf = (post: Post): string[] =>
  post.tags.edges.map((edge) => edge.node.name);

const PRE_CREATED = 'posts.PostPreCreated';
const CREATED = 'posts.PostCreated';
const UPDATED = 'posts.PostUpdated';

describe('a saga coreografada entre posts-api e tagging', () => {
  let author: AuthenticatedApi;
  let subscription: SseSubscription<{ onPostCreated: Post }>;
  let postId: string;
  let completed: { onPostCreated: Post };

  beforeAll(async () => {
    author = await stack.api.asAuthor();
    subscription = await stack.api.subscribe<{ onPostCreated: Post }>(
      'subscription { onPostCreated { id version tags { edges { node { name } } } } }',
    );
    await sleep(500);
  });

  it('o createPost responde PRÉ-CRIADO — versão 1, sem tag', async () => {
    const { createPost } = await author.mutate<{ createPost: Post }>(
      'mutation { createPost(input: { title: "Saga coreografada", content: "c" })' +
        ' { id version tags { edges { node { name } } } } }',
    );
    postId = createPost.id;

    expect(
      createPost.version,
      'v2 aqui significa tagueamento em processo',
    ).toBe(1);
    expect(tagsOf(createPost)).toEqual([]);
  });

  it('a subscription recebe o post COMPLETO depois da volta da saga', async () => {
    completed = await subscription.awaitMatching(
      (event) =>
        event.onPostCreated?.id === postId &&
        event.onPostCreated?.version === 2,
      { describeFailure: () => stack.tagging.tail() },
    );
    subscription.close();

    expect(tagsOf(completed.onPostCreated)).toContain('Untagged');
  });

  it('o event store de CADA serviço tem exatamente os eventos esperados', () => {
    const expected = `${PRE_CREATED},${CREATED}`;
    expect(stack.postsStore.streamOf(postId)).toBe(expected);
    expect(
      stack.taggingStore.streamOf(postId),
      'a fila não é a fonte: o store dele é',
    ).toBe(expected);
  });

  it('o inbox registrou uma linha por mensagem recebida, com a ORIGEM certa', () => {
    expect(stack.postsStore.inbox()).toContain(CREATED);
    expect(stack.postsStore.inbox()).toContain('axonposts-tagging');
    expect(stack.taggingStore.inbox()).toContain(PRE_CREATED);
    expect(stack.taggingStore.inbox()).toContain('quarkus-axon-graphql-posts');
  });

  it('reentregar a MESMA mensagem não produz uma segunda decisão', async () => {
    const identity = stack.postsStore.identityOf(postId, PRE_CREATED);
    const routing = await stack.broker.publish(
      `${PRE_CREATED}.${postId}`,
      AxonEnvelope.of({
        identity,
        origin: 'quarkus-axon-graphql-posts',
        tag: { key: 'postId', value: postId },
        payload: {
          postId,
          title: 'Saga coreografada',
          content: 'c',
          authorId: '00000000-0000-0000-0000-000000000000',
          occurredAt: new Date().toISOString(),
        },
      }),
    );
    expect(routing.routed, 'o broker não roteou: o binding mudou').toBe(true);

    await sleep(4000);
    expect(stack.taggingStore.countEvents(postId, CREATED)).toBe(1);
    expect(stack.taggingStore.inboxRowsFor(identity.identifier)).toBe(1);
  });

  it('o canal de RÉPLICA mantém o stream do Post completo no outro serviço', async () => {
    const { updatePost } = await author.mutate<{
      updatePost: { version: number };
    }>(
      'mutation Editar($id: ID!) { updatePost(input: { id: $id, title: "Saga editada" }) { version } }',
      { id: postId },
    );
    expect(updatePost.version).toBe(3);

    const expected = `${PRE_CREATED},${CREATED},${UPDATED}`;
    await expect
      .poll(() => stack.taggingStore.streamOf(postId), {
        timeout: 20_000,
        interval: 500,
      })
      .toBe(expected);
  });
});
