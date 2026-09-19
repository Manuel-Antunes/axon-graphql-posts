import type { Session } from "@/lib/auth/claims";

import { ApolloProvider } from "./apollo-provider";
import { SessionProvider } from "./session-provider";

/**
 * A ordem importa: `SessionProvider` por FORA, porque é ele quem põe o token em
 * `lib/apollo/token` — e o `ApolloProvider` já monta um cliente que lê de lá na primeira operação.
 */
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
