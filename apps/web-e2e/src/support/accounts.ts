export interface Account {
  readonly email: string;
  readonly password: string;
  readonly role: string;
}

export interface Accounts {
  readonly author: Account;
  readonly reader: Account;
}

const PASSWORD = 'segredo123';

export const accounts: Accounts = {
  author: { email: 'manuel@example.com', password: PASSWORD, role: 'author' },
  reader: { email: 'leitor@example.com', password: PASSWORD, role: 'user' },
};
