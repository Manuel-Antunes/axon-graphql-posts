import { disableFragmentWarnings } from '@apollo/client';

/**
 * Cala um aviso do `graphql-tag` que, NESTE arranjo, não pode indicar problema.
 *
 * <h2>O aviso</h2>
 * <pre>
 * Warning: fragment with name PostCard_post already exists.
 * graphql-tag enforces all fragment names across your application to be unique
 * </pre>
 *
 * <h2>Por que ele aparece</h2>
 * O `client-preset` do codegen <b>embute</b> a definição de cada fragmento em todo documento que o
 * usa. `PostCard_post` aparece dentro de `PostList_connection`, que aparece dentro de `FeedPosts` —
 * então o mesmo fragmento é parseado várias vezes, e o registro global do `graphql-tag` reclama da
 * segunda em diante.
 *
 * <h2>Por que calar é seguro aqui, e não em geral</h2>
 * O aviso existe para pegar dois fragmentos DIFERENTES com o mesmo nome — um deles venceria o outro
 * em silêncio. Aqui isso não pode acontecer: os documentos não são escritos à mão, são gerados de um
 * schema por um gerador que <b>falha</b> se dois fragmentos tiverem o mesmo nome. A garantia mudou de
 * lugar — do runtime para o `pnpm codegen` —, e o que sobra no console é barulho.
 *
 * Importar este módulo é o suficiente; ele não exporta nada.
 */
disableFragmentWarnings();
