import { graphql } from "@/gql";

/**
 * A query desta página. `me` EXIGE token — é o contraste com `/feed`, que é pública.
 *
 * O prefetch funciona porque o cliente de servidor (`lib/apollo/rsc.ts`) lê o ID token do cookie
 * `httpOnly` e o põe no header. É o que torna a página autenticada renderizável no servidor sem o
 * token nunca passar pelo JavaScript: ele sai do cookie, vai no header, e o que volta é só o
 * resultado.
 */
export const MeQuery = graphql(`
    query Me {
        me {
            ...IdentityPanel_user
        }
    }
`);
