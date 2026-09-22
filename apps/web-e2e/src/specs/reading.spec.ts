import { expect, test } from '../fixtures/test';

test('um post escrito por um autor chega a quem nunca entrou, com autor e tags', async ({
  page,
  browser,
  accounts,
  signIn,
}) => {
  test.slow();

  const title = `Leitura pública ${Date.now()}`;

  await signIn(accounts.author);
  await page.goto('/posts/new');
  await page.getByLabel('Título').fill(title);
  await page.getByLabel('Conteúdo').fill('o corpo que o feed não carrega');
  await page.getByRole('button', { name: 'Publicar' }).click();
  const href = await page
    .getByRole('link', { name: 'Abrir o post' })
    .getAttribute('href');

  const anonymous = await browser.newContext();
  const visitor = await anonymous.newPage();

  await expect(async () => {
    await visitor.goto(href!);
    await expect(visitor.getByText('v2 · saga fechada')).toBeVisible({
      timeout: 5_000,
    });
  }).toPass({ timeout: 90_000 });

  await expect(visitor.getByRole('heading', { name: title })).toBeVisible();
  await expect(
    visitor.getByText('Untagged').first(),
    'a tag veio do outro serviço e é lida sem token',
  ).toBeVisible();
  await expect(
    visitor.getByText(accounts.author.email).first(),
    'a autoria é do post, e ler não pede sessão',
  ).toBeVisible();
  await expect(
    visitor.getByRole('link', { name: 'Entrar' }),
    'quem lê continua anônimo',
  ).toBeVisible();

  await visitor.goto('/feed');
  await expect(visitor.getByRole('link', { name: title })).toBeVisible();

  await anonymous.close();
});
