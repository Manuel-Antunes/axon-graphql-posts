import type { Session } from '@/lib/auth/claims';

import { ApolloProvider } from './apollo-provider';
import { SessionProvider } from './session-provider';

export function Providers({
  session,
  children,
}: {
  session: Session | null;
  children: React.ReactNode;
}) {
  return (
    <SessionProvider initialSession={session}>
      <ApolloProvider>{children}</ApolloProvider>
    </SessionProvider>
  );
}
