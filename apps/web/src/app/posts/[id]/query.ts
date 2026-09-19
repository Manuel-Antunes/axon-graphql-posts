import { graphql } from "@/gql";

/**
 * A query desta página. Lida por dois lugares, como manda a regra:
 * o `page.tsx` a passa ao `PreloadQuery` (servidor) e o `_hooks/use-post.ts` a lê com
 * `useSuspenseQuery` (cliente).
 *
 * Os dois fragmentos espalhados aqui são de componentes diferentes — o artigo e o editor —, e os dois
 * pedem `content`. Isso não é transferência duplicada: o servidor resolve o campo uma vez e o Apollo o
 * normaliza uma vez. Dois fragmentos pedindo o mesmo campo é exatamente o caso que a composição de
 * fragmentos existe para resolver.
 *
 * As MUTATIONS não estão aqui, e a razão é a definição de "query da página": mutation não se faz
 * prefetch. Elas moram no hook que as dispara.
 */
export const PostByIdQuery = graphql(`
    query PostById($id: ID!) {
        post(id: $id) {
            id
            version
            ...PostArticle_post
            ...PostEditor_post
        }
    }
`);
