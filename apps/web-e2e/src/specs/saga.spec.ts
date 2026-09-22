import { expect, test } from '../fixtures/test';
import { AxonEnvelope } from '../support/broker';

const PRE_CREATED = 'posts.PostPreCreated';
const CREATED = 'posts.PostCreated';
const UPDATED = 'posts.PostUpdated';

const POSTS_API_ORIGIN = 'quarkus-axon-graphql-posts';
const TAGGING_ORIGIN = 'axonposts-tagging';

test.describe.configure({ mode: 'serial' });

test.describe('a saga coreografada, escrita no navegador', () => {
  let postId: string;

  test('o formulário responde PRÉ-CRIADO: versão 1, sem tag', async ({
    page,
    accounts,
    signIn,
  }) => {
    await signIn(accounts.author);
    await page.goto('/posts/new');

    await page.getByLabel('Título').fill('Saga pelo navegador');
    await page.getByLabel('Conteúdo').fill('escrito no formulário');
    await page.getByRole('button', { name: 'Publicar' }).click();

    await expect(
      page.getByText('Resposta da mutation — versão 1'),
    ).toBeVisible();
    await expect(
      page.getByText('sem tag — o post ainda está na versão 1'),
    ).toBeVisible();

    const href = await page
      .getByRole('link', { name: 'Abrir o post' })
      .getAttribute('href');
    postId = href!.split('/').pop()!;
    expect(postId).toMatch(/^[0-9a-f-]{36}$/);
  });

  test('a página do post alcança a versão 2, com a tag que o OUTRO serviço decidiu', async ({
    page,
  }) => {
    await expect(async () => {
      await page.goto(`/posts/${postId}`);
      await expect(page.getByText('v2 · saga fechada')).toBeVisible({
        timeout: 5_000,
      });
    }).toPass({ timeout: 60_000 });

    await expect(page.getByText('Untagged').first()).toBeVisible();
  });

  test('o event store de CADA serviço tem exatamente os eventos esperados', ({
    postsStore,
    taggingStore,
  }) => {
    const expected = `${PRE_CREATED},${CREATED}`;

    expect(postsStore.streamOf(postId)).toBe(expected);
    expect(
      taggingStore.streamOf(postId),
      'a fila não é a fonte: o store dele é',
    ).toBe(expected);
  });

  test('a projeção materializou o que a tela mostrou', ({ postsStore }) => {
    expect(postsStore.versionOf(postId)).toBe(2);
    expect(postsStore.tagsOf(postId)).toEqual(['Untagged']);
  });

  test('o inbox registrou uma linha por mensagem recebida, com a ORIGEM certa', ({
    postsStore,
    taggingStore,
  }) => {
    expect(postsStore.inbox()).toContain(CREATED);
    expect(postsStore.inbox()).toContain(TAGGING_ORIGIN);
    expect(taggingStore.inbox()).toContain(PRE_CREATED);
    expect(taggingStore.inbox()).toContain(POSTS_API_ORIGIN);
  });

  test('reentregar a MESMA mensagem não produz uma segunda decisão', async ({
    broker,
    postsStore,
    taggingStore,
  }) => {
    const identity = postsStore.identityOf(postId, PRE_CREATED);

    const routing = await broker.publish(
      `${PRE_CREATED}.${postId}`,
      AxonEnvelope.of({
        identity,
        origin: POSTS_API_ORIGIN,
        tag: { key: 'postId', value: postId },
        payload: {
          postId,
          title: 'Saga pelo navegador',
          content: 'escrito no formulário',
          authorId: '00000000-0000-0000-0000-000000000000',
          occurredAt: new Date().toISOString(),
        },
      }),
    );

    expect(routing.routed, 'o broker não roteou: o binding mudou').toBe(true);

    await new Promise((resolve) => setTimeout(resolve, 4000));

    expect(
      taggingStore.countEvents(postId, CREATED),
      'inbox e agregado seguraram a duplicata',
    ).toBe(1);
    expect(taggingStore.inboxRowsFor(identity.identifier)).toBe(1);
  });

  test('o canal de RÉPLICA mantém o stream do Post completo no outro serviço', async ({
    page,
    accounts,
    signIn,
    taggingStore,
  }) => {
    await signIn(accounts.author);
    await page.goto(`/posts/${postId}`);

    await page.getByLabel('Novo título').fill('Saga editada');
    await page
      .getByRole('button', { name: 'Enviar o que foi preenchido' })
      .click();

    await expect(page.getByText('v3 · editado')).toBeVisible();

    const expected = `${PRE_CREATED},${CREATED},${UPDATED}`;
    await expect
      .poll(() => taggingStore.streamOf(postId), {
        timeout: 20_000,
        intervals: [500],
      })
      .toBe(expected);
  });
});
