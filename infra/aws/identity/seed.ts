import { pool } from '.';

/**
 * Os MESMOS três usuários do realm, com os mesmos e-mails, nomes e senhas — porque é isso que faz o
 * `e2e.sh` ser o mesmo roteiro contra os dois emissores, e o que mantém `promovido@example.com`
 * existindo para exercitar a promoção Reader → Author.
 *
 * `messageAction: "SUPPRESS"` porque sem ele o Cognito tenta ENVIAR um e-mail de boas-vindas para
 * `@example.com` a cada deploy — e falha, ruidosamente.
 */
export function seed(
  name: string,
  email: string,
  fullName: string,
  groups: string[],
) {
  const user = new aws.cognito.User(name, {
    ...pool,
    username: email,
    password: 'segredo123',
    messageAction: 'SUPPRESS',
    attributes: {
      email,
      email_verified: 'true',
      name: fullName,
    },
  });
  groups.forEach(
    (group) =>
      new aws.cognito.UserInGroup(`${name}In${group}`, {
        ...pool,
        username: user.username,
        groupName: group,
      }),
  );
  return user;
}
