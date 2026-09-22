import { expect, test } from '../fixtures/test';

const CREATE = `
  mutation Criar($title: String!) {
    createPost(input: { title: $title, content: "para a subscription" }) { id }
  }
`;

const UPDATE = `
  mutation Editar($id: ID!, $title: String!) {
    updatePost(input: { id: $id, title: $title }) { version }
  }
`;

test('a subscription chega ao navegador pelo proxy, e anuncia a versão 2', async ({
  page,
  accounts,
  signIn,
  graphql,
}) => {
  test.slow();

  await signIn(accounts.author);
  await page.goto('/live');
  await expect(page.getByText('Conectado. Nenhum evento ainda.')).toBeVisible({
    timeout: 60_000,
  });

  const title = `Saga observada em ${Date.now()}`;
  const created = await graphql<{ createPost: { id: string } }>(CREATE, {
    title,
  });
  expect(created.errors, JSON.stringify(created.errors)).toBeUndefined();

  const announced = page
    .getByRole('listitem')
    .filter({ hasText: title })
    .first();

  await expect(announced).toBeVisible({ timeout: 90_000 });
  await expect(announced.getByText('onPostCreated')).toBeVisible();
  await expect(
    announced.getByText('v2 · saga fechada'),
    'onPostCreated anuncia a saga fechada, não o nascimento',
  ).toBeVisible();

  const edited = `${title} — editado`;
  const update = await graphql<{ updatePost: { version: number } }>(UPDATE, {
    id: created.data!.createPost.id,
    title: edited,
  });
  expect(update.data?.updatePost.version).toBe(3);

  const reedited = page
    .getByRole('listitem')
    .filter({ hasText: edited })
    .first();

  await expect(reedited).toBeVisible({ timeout: 60_000 });
  await expect(
    reedited.getByText('onPostUpdated'),
    'onPostUpdated começa na 3: a conclusão em 2 é um PostCreated',
  ).toBeVisible();
});
