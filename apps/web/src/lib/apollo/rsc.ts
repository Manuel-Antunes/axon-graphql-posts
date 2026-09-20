import 'server-only';

import { HttpLink } from '@apollo/client';
import {
  ApolloClient,
  registerApolloClient,
} from '@apollo/client-integration-nextjs';

import { readSession } from '@/lib/auth/cookies';
import { GRAPHQL_UPSTREAM } from '@/lib/env';

import './fragment-warnings';

import { createCache } from './cache';

export const { getClient, query, PreloadQuery } = registerApolloClient(
  async () => {
    const session = await readSession();

    return new ApolloClient({
      cache: createCache(),
      link: new HttpLink({
        uri: GRAPHQL_UPSTREAM,
        headers: session ? { authorization: `Bearer ${session.idToken}` } : {},
        fetchOptions: { cache: 'no-store' },
      }),
    });
  },
);
