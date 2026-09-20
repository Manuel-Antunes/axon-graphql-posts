'use client';

import { ApolloNextAppProvider } from '@apollo/client-integration-nextjs';

import { makeClient } from '@/lib/apollo/client';

export function ApolloProvider({ children }: { children: React.ReactNode }) {
  return (
    <ApolloNextAppProvider makeClient={makeClient}>
      {children}
    </ApolloNextAppProvider>
  );
}
