import { test as base, expect } from '@playwright/test';

import type { Account, Accounts } from '../support/accounts';
import { accounts as seeded } from '../support/accounts';
import { Broker } from '../support/broker';
import { Container } from '../support/docker';
import { EventStore } from '../support/event-store';
import { Keycloak } from '../support/keycloak';
import { GRAPHQL_URL } from '../support/stack';

export interface SignIn {
  (account: Account): Promise<void>;
}

export interface GraphQlAnswer<T> {
  data?: T;
  errors?: Array<{ message: string; extensions?: Record<string, unknown> }>;
}

export interface GraphQl {
  <T>(
    query: string,
    variables?: Record<string, unknown>,
  ): Promise<GraphQlAnswer<T>>;
}

interface Fixtures {
  accounts: Accounts;
  signIn: SignIn;
  graphql: GraphQl;
  keycloak: Keycloak;
  graphqlUrl: string;
  postsStore: EventStore;
  taggingStore: EventStore;
  broker: Broker;
}

const postgres = new Container('quarkus-axonposts-postgres');
const rabbitmq = new Container('quarkus-axonposts-rabbitmq');

export const test = base.extend<Fixtures>({
  accounts: async ({}, use) => {
    await use(seeded);
  },

  keycloak: async ({}, use) => {
    await use(new Keycloak());
  },

  graphqlUrl: async ({}, use) => {
    await use(GRAPHQL_URL);
  },

  postsStore: async ({}, use) => {
    await use(new EventStore(postgres, 'axonposts'));
  },

  taggingStore: async ({}, use) => {
    await use(new EventStore(postgres, 'axonposts_tagging'));
  },

  broker: async ({}, use) => {
    await use(new Broker(rabbitmq));
  },

  graphql: async ({ page }, use) => {
    await use(async (query, variables) => {
      if (!page.url().startsWith('http')) {
        await page.goto('/');
      }
      return page.evaluate(
        async ([document, args]) => {
          const response = await fetch('/api/graphql', {
            method: 'POST',
            headers: { 'content-type': 'application/json' },
            body: JSON.stringify({ query: document, variables: args }),
          });
          return response.json();
        },
        [query, variables ?? {}] as const,
      );
    });
  },

  signIn: async ({ page }, use) => {
    await use(async (account: Account) => {
      await page.goto('/login');
      await page.getByLabel('E-mail').fill(account.email);
      await page.getByLabel('Senha').fill(account.password);
      await page.getByRole('button', { name: 'Entrar' }).click();
      await expect(page.getByText(account.email).first()).toBeVisible();
    });
  },
});

export { expect } from '@playwright/test';
