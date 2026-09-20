import { ApolloClient } from "@apollo/client-integration-nextjs";
import { HttpLink, split } from "@apollo/client";

import { GRAPHQL_PROXY, GRAPHQL_UPSTREAM } from "@/lib/env";

import "./fragment-warnings";

import { createCache } from "./cache";
import { GraphQLSSELink } from "./links/sse-link";

/**
 * O cliente dos COMPONENTES DE CLIENTE. Um por aba, criado pelo `ApolloNextAppProvider`.
 *
 * <h2>Ele fala com o PROXY, não com a API</h2>
 * As duas pontas apontam para `/api/graphql`, da própria origem. O que isso compra está no Javadoc
 * de `app/api/graphql/route.ts`; o que importa aqui é o que <b>sumiu</b> por causa disso: o
 * `authLink` e o `lib/apollo/token.ts`. Não há mais token em memória no navegador — quem o põe no
 * header é o proxy, lendo o cookie `httpOnly`. Um link a menos, e uma credencial a menos circulando
 * onde qualquer script alcança.
 *
 * <h2>A cadeia</h2>
 * <pre>
 *   split(é subscription?)
 *     ├── sim ──► GraphQLSSELink   POST /api/graphql, Accept: text/event-stream
 *     └── não ──► HttpLink         POST /api/graphql, Accept: application/json
 * </pre>
 * As duas batem no MESMO caminho: quem escolhe a porta é o cabeçalho. O proxy repassa isso adiante.
 *
 * <h2>A URL no SERVIDOR</h2>
 * Este mesmo `makeClient` roda quando o Next renderiza um componente de cliente no servidor, e lá um
 * caminho relativo não resolve. O fallback aponta para a API de verdade — mas ele não deve ser
 * exercitado: toda query de página vem pelo `PreloadQuery` (`lib/apollo/rsc.ts`), que já roda no
 * servidor com o token do cookie. Se alguma requisição sair por aqui durante o SSR, ela sairá
 * ANÔNIMA, e isso é um defeito a consertar na página, não aqui.
 */
export function makeClient(): ApolloClient {
    const uri = typeof window === "undefined" ? GRAPHQL_UPSTREAM : GRAPHQL_PROXY;

    return new ApolloClient({
        link: split(
            (operation) => operation.operationType === "subscription",
            new GraphQLSSELink(uri),
            new HttpLink({ uri }),
        ),
        cache: createCache(),
        devtools: { enabled: process.env.NODE_ENV !== "production" },
    });
}
