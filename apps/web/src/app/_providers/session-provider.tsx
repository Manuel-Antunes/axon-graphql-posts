"use client";

import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";

import { refreshSession } from "@/app/actions/auth";
import { isAuthor as hasAuthorGroup, type Session } from "@/lib/auth/claims";

/**
 * Quem sabe que há uma sessão — e quem entrega o token ao Apollo.
 *
 * <h2>A sessão inicial vem do SERVIDOR</h2>
 * O `layout.tsx` lê o cookie `httpOnly` e passa a sessão por prop. Não há chamada de rede no primeiro
 * render, e não há aquele piscar de "deslogado → logado" que aparece quando o cliente descobre a
 * sessão sozinho depois de montar.
 *
 * <h2>Ele NÃO carrega o token</h2>
 * Carregava: havia aqui uma atribuição a `lib/apollo/token`, feita durante o render, para que o
 * `authLink` do Apollo achasse o bearer antes da primeira query. Os dois sumiram quando o cliente
 * passou a falar pelo proxy — agora quem põe o `Authorization` é o servidor, lendo o cookie
 * `httpOnly`. O que este provider guarda é só o que a interface mostra, mais o relógio da renovação.
 */
interface SessionContextValue {
    session: Session | null;
    isAuthor: boolean;
    /** Renova agora. Usada pelo relógio de expiração — o cookie novo é escrito pela server action. */
    renew: () => Promise<Session | null>;
}

const SessionContext = createContext<SessionContextValue>({
    session: null,
    isAuthor: false,
    renew: async () => null,
});

export function SessionProvider({
    initialSession,
    children,
}: {
    initialSession: Session | null;
    children: React.ReactNode;
}) {
    const [session, setSession] = useState<Session | null>(initialSession);

    const renew = useCallback(async () => {
        const renewed = await refreshSession();
        setSession(renewed);
        return renewed;
    }, []);

    // O ID token do Cognito vale 1 hora (o default, que `infra/aws/identity/index.ts` deliberadamente
    // não mexe). Renovar um minuto antes evita a janela em que uma mutation sai com token vencido.
    useEffect(() => {
        if (!session) return;
        const delay = Math.max(session.expiresAt - Date.now() - 60_000, 0);
        const timer = setTimeout(() => void renew(), delay);
        return () => clearTimeout(timer);
    }, [session, renew]);

    const value = useMemo(
        () => ({ session, isAuthor: hasAuthorGroup(session), renew }),
        [session, renew],
    );

    return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>;
}

export function useSession(): SessionContextValue {
    return useContext(SessionContext);
}
