import { expect, test } from '../fixtures/test';

const SESSION_COOKIE = 'axonposts.id_token';

test('o formulário entra pelo provedor de identidade do ambiente', async ({
  page,
  accounts,
  signIn,
}) => {
  await signIn(accounts.author);

  await expect(page.getByText(accounts.author.email).first()).toBeVisible();
  await expect(page.getByText('author', { exact: true })).toBeVisible();
});

test('a sessão vive num cookie httpOnly, e o JavaScript da página não a alcança', async ({
  page,
  context,
  accounts,
  signIn,
}) => {
  await signIn(accounts.author);

  const session = (await context.cookies()).find(
    (cookie) => cookie.name === SESSION_COOKIE,
  );

  expect(session, `nenhum ${SESSION_COOKIE} foi escrito`).toBeDefined();
  expect(session!.httpOnly, 'o token chegaria ao script da página').toBe(true);
  expect(await page.evaluate(() => document.cookie)).not.toContain(
    SESSION_COOKIE,
  );
});

test('a sessão sobrevive a um reload: quem a guarda é o cookie, não a memória', async ({
  page,
  accounts,
  signIn,
}) => {
  await signIn(accounts.author);

  await page.reload();

  await expect(page.getByText(accounts.author.email).first()).toBeVisible();
});

test('o token que o web guardou é aceito pela posts-api', async ({
  context,
  accounts,
  signIn,
  graphqlUrl,
}) => {
  await signIn(accounts.author);
  const session = (await context.cookies()).find(
    (cookie) => cookie.name === SESSION_COOKIE,
  )!;

  const response = await fetch(graphqlUrl, {
    method: 'POST',
    headers: {
      'content-type': 'application/json',
      'authorization': `Bearer ${session.value}`,
    },
    body: JSON.stringify({ query: '{ me { id email } }' }),
  });
  const answer = (await response.json()) as {
    data?: { me?: { email: string } };
    errors?: unknown;
  };

  expect(answer.errors, JSON.stringify(answer.errors)).toBeUndefined();
  expect(answer.data?.me?.email).toBe(accounts.author.email);
});

test('uma senha errada é recusada, e o motivo aparece na tela', async ({
  page,
  accounts,
}) => {
  await page.goto('/login');
  await page.getByLabel('E-mail').fill(accounts.author.email);
  await page.getByLabel('Senha').fill('não-é-a-senha');
  await page.getByRole('button', { name: 'Entrar' }).click();

  await expect(page.getByText('invalid_grant')).toBeVisible();
  await expect(page.getByRole('link', { name: 'Entrar' })).toBeVisible();
});

test('sair apaga a sessão', async ({ page, context, accounts, signIn }) => {
  await signIn(accounts.author);

  await page.getByRole('button', { name: 'Sair' }).click();
  await page.waitForURL('**/login');

  expect(
    (await context.cookies()).find((cookie) => cookie.name === SESSION_COOKIE)
      ?.value ?? '',
  ).toBe('');
});
