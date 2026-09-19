"use server";

/**
 * As server actions de autenticação. É a ÚNICA porta entre esta aplicação e o Cognito.
 *
 * <h2>Por que server action, e não um `fetch` do navegador</h2>
 * O `InitiateAuth` funciona do navegador — é uma API pública, com CORS liberado. Fazer assim
 * custaria duas coisas: a senha atravessaria o JavaScript da página (onde qualquer script de
 * terceiro a alcança) e o refresh token teria de ficar em `localStorage`, legível e permanente.
 *
 * Com a ação no servidor, o formulário faz um POST comum, o Cognito responde para o SERVIDOR, os
 * tokens vão para cookies `httpOnly` e o navegador recebe de volta só o que precisa: o ID token em
 * memória, para o header `Authorization` do Apollo.
 *
 * <h2>O contrato com o formulário</h2>
 * `signIn` tem a assinatura de `useActionState` — `(estadoAnterior, formData)` — porque é o que faz
 * o React 19 tratar a pendência e o erro sem uma linha de estado escrita à mão. O retorno é sempre
 * um objeto: um `throw` aqui viraria a página de erro do Next, e senha errada não é um defeito.
 */

import { z } from "zod";

import { publicSession, toSession, type Session } from "@/lib/auth/claims";
import { clearSession, readSession, storeSession, storedRefreshToken } from "@/lib/auth/cookies";
import { CognitoError, refreshTokens, signInWithPassword } from "@/lib/auth/cognito";

export type SignInState = {
    status: "idle" | "error" | "ok";
    message?: string;
    /** O `__type` do Cognito. A interface o mostra porque, num roteiro de teste, ele É a informação. */
    code?: string;
    email?: string;
    /** Para onde ir depois. Quem navega é o CLIENTE, e com recarga — ver abaixo. */
    next?: string;
};

const credentials = z.object({
    email: z.email("Informe um e-mail válido."),
    password: z.string().min(1, "Informe a senha."),
});

export async function signIn(_previous: SignInState, formData: FormData): Promise<SignInState> {
    const parsed = credentials.safeParse({
        email: String(formData.get("email") ?? "").trim(),
        password: String(formData.get("password") ?? ""),
    });

    if (!parsed.success) {
        return {
            status: "error",
            code: "ValidationError",
            message: parsed.error.issues[0]?.message ?? "Credenciais inválidas.",
            email: String(formData.get("email") ?? ""),
        };
    }

    try {
        const tokens = await signInWithPassword(parsed.data.email, parsed.data.password);
        await storeSession(tokens);
    } catch (error) {
        const failure =
            error instanceof CognitoError
                ? error
                : new CognitoError("NetworkError", "Não foi possível falar com o Cognito.");
        return {
            status: "error",
            code: failure.code,
            message: failure.message,
            email: parsed.data.email,
        };
    }

    /*
     * A ação NÃO redireciona — ela devolve para onde ir, e quem navega é o formulário, com uma
     * recarga de página inteira. Isso custa uma explicação, porque um `redirect()` aqui seria o
     * óbvio. E ele FALHA em produção, de um jeito que não aparece em `next dev`:
     *
     *   1. o `layout.tsx` lê o cookie da sessão — então o payload do layout DEPENDE do cookie;
     *   2. em produção o Next faz PREFETCH dos `<Link>` visíveis. Na tela de login, os links do
     *      cabeçalho são buscados enquanto o usuário ainda é anônimo, e o payload anônimo do layout
     *      fica no Router Cache do cliente;
     *   3. um `redirect()` da ação vira navegação SUAVE, que reaproveita exatamente esse payload. O
     *      cookie está gravado, o servidor já sabe quem é — e o cabeçalho continua dizendo "Entrar".
     *
     * Medido: em `next dev` (sem prefetch) o `redirect` funcionava; no CloudFront, não. O
     * Medido no CloudFront: o `redirect` reaproveitava o payload anônimo já buscado.
     *
     * Uma sessão nova é um documento novo. A recarga é a única forma de garantir que TUDO — layout
     * incluído — seja montado com o cookie que acabou de existir.
     *
     * <h2>E por que NÃO há `revalidatePath` aqui</h2>
     * Havia, e ele era a causa de um segundo problema, mais difícil de ver. `revalidatePath` faz o
     * roteador REVALIDAR a rota atual — que é `/login`. O `page.tsx` de lá faz
     * `if (await readSession()) redirect("/feed")`: com o cookie recém-gravado, a revalidação
     * disparava esse redirect, o roteador navegava SOZINHO para `/feed` (suave, com o layout
     * anônimo do prefetch) e desmontava este formulário antes de o efeito rodar. A recarga nunca
     * acontecia.
     *
     * Sem ele não se perde nada: uma recarga de página inteira não reaproveita cache de rota nenhum.
     */
    return { status: "ok", next: String(formData.get("next") ?? "/feed") };
}

/** Mesma assimetria do `signIn`, e pela mesma razão: quem navega é o cliente, com recarga. */
export async function signOut() {
    await clearSession();
}

/**
 * O que o provider chama na montagem de cada aba: devolve a sessão do cookie, já renovada se estava
 * para vencer. É aqui que o ID token sai do cookie `httpOnly` e entra na memória do navegador.
 */
export async function currentSession(): Promise<Session | null> {
    const session = await readSession();
    if (session && session.expiresAt - Date.now() > 60_000) return publicSession(session);
    return refreshSession();
}

/**
 * Renova o ID token com o refresh token do cookie.
 *
 * Devolve `null` — e limpa os cookies — quando não há refresh token ou ele já não vale. O cliente lê
 * isso como "deslogado" e manda para o login, que é o comportamento certo depois de 30 dias.
 */
export async function refreshSession(): Promise<Session | null> {
    const refreshToken = await storedRefreshToken();
    if (!refreshToken) {
        await clearSession();
        return null;
    }

    try {
        const tokens = await refreshTokens(refreshToken);
        await storeSession({ ...tokens, refreshToken });
        // `publicSession` porque o retorno atravessa para o navegador: o token novo fica no cookie.
        return publicSession(toSession(tokens.idToken));
    } catch {
        await clearSession();
        return null;
    }
}
