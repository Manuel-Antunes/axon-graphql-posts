import { pool } from '.';

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
