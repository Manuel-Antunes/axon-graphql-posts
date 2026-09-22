import { expect, test } from '../fixtures/test';

const CREATE = `
  mutation Criar($title: String!) {
    createPost(input: { title: $title, content: "corpo" }) { id version }
  }
`;

test('sem sessão, escrever é oferecido como login — não como formulário', async ({
  page,
}) => {
  await page.goto('/posts/new');

  await expect(page.getByText('Entre para escrever')).toBeVisible();
  await expect(page.getByLabel('Título')).toBeHidden();
});

test('com sessão mas sem a role, a tela diz o que falta', async ({
  page,
  accounts,
  signIn,
}) => {
  await signIn(accounts.reader);
  await page.goto('/posts/new');

  await expect(
    page.getByText('Esta conta não tem a role author'),
  ).toBeVisible();
  await expect(page.getByLabel('Título')).toBeHidden();
});

test('a recusa é do SERVIDOR, e não da tela', async ({
  accounts,
  signIn,
  graphql,
}) => {
  await signIn(accounts.reader);

  const refused = await graphql<{ createPost: unknown }>(CREATE, {
    title: 'Um leitor tentando escrever',
  });

  expect(refused.data?.createPost ?? null).toBeNull();
  expect(refused.errors?.[0]?.extensions?.code).toBe('FORBIDDEN');
});

test('com a role, o formulário está lá', async ({ page, accounts, signIn }) => {
  await signIn(accounts.author);
  await page.goto('/posts/new');

  await expect(page.getByLabel('Título')).toBeVisible();
  await expect(page.getByRole('button', { name: 'Publicar' })).toBeEnabled();
});

test('ler é anônimo de propósito', async ({ page, accounts, signIn }) => {
  await signIn(accounts.author);
  await page.goto('/posts/new');
  await page.getByLabel('Título').fill('Um post que qualquer um lê');
  await page.getByLabel('Conteúdo').fill('sem token nenhum');
  await page.getByRole('button', { name: 'Publicar' }).click();
  const href = await page
    .getByRole('link', { name: 'Abrir o post' })
    .getAttribute('href');

  await page.context().clearCookies();
  await page.goto(href!);

  await expect(
    page.getByRole('heading', { name: 'Um post que qualquer um lê' }),
  ).toBeVisible();
});

test('`me` é polimórfico: Author para quem escreve, User para quem só lê', async ({
  page,
  accounts,
  signIn,
}) => {
  await signIn(accounts.author);
  await page.goto('/me');
  await expect(page.getByText('Author', { exact: true })).toBeVisible();

  await page.context().clearCookies();
  await signIn(accounts.reader);
  await page.goto('/me');
  await expect(page.getByText('Reader', { exact: true })).toBeVisible();
});
