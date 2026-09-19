/**
 * Os dois endereços do GraphQL — e eles NÃO são o mesmo.
 *
 * <pre>
 *   navegador ──► /api/graphql ──► GRAPHQL_UPSTREAM
 *                  (proxy)
 *   servidor  ─────────────────► GRAPHQL_UPSTREAM
 * </pre>
 *
 * O <b>navegador</b> fala com o proxy da própria aplicação (`app/api/graphql/route.ts`). O que isso
 * compra está no Javadoc de lá: mesma origem (nenhum CORS), o ID token nunca sai do cookie
 * `httpOnly`, e um lugar só onde o streaming pode ser resolvido.
 *
 * O <b>servidor</b> — o `PreloadQuery` de `lib/apollo/rsc.ts` e o próprio proxy — fala direto com a
 * API. Passar pelo proxy ali seria a aplicação fazendo uma requisição HTTP para si mesma.
 */
export const GRAPHQL_UPSTREAM =
    process.env.NEXT_PUBLIC_GRAPHQL_URL ?? "http://localhost:8080/graphql";

/** O caminho do proxy. Relativo de propósito: ele é sempre a mesma origem da página. */
export const GRAPHQL_PROXY = "/api/graphql";

export const COGNITO_ISSUER = process.env.NEXT_PUBLIC_COGNITO_ISSUER ?? "";

/** O host da API DE VERDADE, para a interface dizer contra quem ela está rodando. */
export function upstreamHost(): string {
    try {
        return new URL(GRAPHQL_UPSTREAM).host;
    } catch {
        return GRAPHQL_UPSTREAM;
    }
}

/** Se o alvo é a stack em Lambda — o que muda o que a página `/live` pode prometer. */
export const UPSTREAM_IS_LAMBDA = GRAPHQL_UPSTREAM.includes("execute-api");
