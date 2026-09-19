import "server-only";

import { cookies } from "next/headers";

import { toSession, type ServerSession } from "./claims";

/**
 * Onde a sessão mora entre uma requisição e outra: DOIS cookies, os dois `httpOnly`.
 *
 * <h2>Por que o ID token também é `httpOnly`, se o navegador precisa dele</h2>
 * Porque "precisar" e "guardar" são coisas diferentes. O Apollo manda o token no header
 * `Authorization` de cada requisição, e para isso ele precisa do valor <b>em memória</b> — não de um
 * cookie legível. Quem entrega esse valor é uma server action (`currentSession`), chamada na
 * montagem do provider: o token vai para uma variável de módulo e some quando a aba fecha.
 *
 * O que se ganha: nada em `localStorage`, nada em cookie legível por script. O que se perde: uma
 * chamada ao servidor no primeiro render de cada aba — e ela cabe, porque a página já é renderizada
 * no servidor de qualquer jeito.
 *
 * <h2>`sameSite: "lax"` e não `"strict"`</h2>
 * `strict` quebraria a volta de qualquer navegação de origem externa — inclusive um link colado no
 * navegador —, e o que este cookie protege não é uma operação de escrita por si: toda escrita passa
 * pelo header `Authorization`, que um site terceiro não consegue montar.
 */
const ID_TOKEN = "axonposts.id_token";
const REFRESH_TOKEN = "axonposts.refresh_token";

const baseCookie = {
    httpOnly: true,
    sameSite: "lax" as const,
    path: "/",
    // Em produção a origem é o CloudFront, que é https. Em `next dev` é http, e `secure` faria o
    // navegador descartar o cookie em silêncio — o sintoma seria "o login não persiste".
    secure: process.env.NODE_ENV === "production",
};

export async function storeSession(tokens: {
    idToken: string;
    refreshToken?: string;
    expiresIn: number;
}) {
    const jar = await cookies();
    jar.set(ID_TOKEN, tokens.idToken, { ...baseCookie, maxAge: tokens.expiresIn });
    if (tokens.refreshToken) {
        // 30 dias: o default do Cognito para refresh token. Ver `infra/aws/identity/index.ts`, que
        // deliberadamente não mexe na validade dos tokens.
        jar.set(REFRESH_TOKEN, tokens.refreshToken, { ...baseCookie, maxAge: 60 * 60 * 24 * 30 });
    }
}

export async function clearSession() {
    const jar = await cookies();
    jar.delete(ID_TOKEN);
    jar.delete(REFRESH_TOKEN);
}

export async function storedRefreshToken(): Promise<string | null> {
    return (await cookies()).get(REFRESH_TOKEN)?.value ?? null;
}

/**
 * Lê a sessão — COM o token, porque quem chama roda no servidor: o layout, o `PreloadQuery` e o
 * proxy. O que desce para o navegador passa por `publicSession`.
 *
 * Lê SEM escrever nada — e essa restrição é do Next, não um zelo: um Server Component não
 * pode gravar cookie (a resposta já começou a ser transmitida). Quem renova é a server action
 * `refreshSession`, chamada pelo provider quando o `expiresAt` se aproxima.
 */
export async function readSession(): Promise<ServerSession | null> {
    const raw = (await cookies()).get(ID_TOKEN)?.value;
    if (!raw) return null;
    const session = toSession(raw);
    if (!session) return null;
    return session.expiresAt <= Date.now() ? null : session;
}
