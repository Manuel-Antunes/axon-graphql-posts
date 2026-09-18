/**
 * <b>O que as queries de Post devolvem.</b> {@link dev.manuelantunes.axonposts.application.post.view.PostView}
 * é o resultado de uma query, {@code PostPage} é uma fatia dele, e o {@code PostViewMapper} é a travessia
 * entidade → view.
 *
 * <h2>Por que isto é da aplicação, e não da apresentação</h2>
 * Porque atravessa o query bus. {@code FindPost} devolve {@code PostView}, {@code FindAllPosts} devolve
 * {@code PostPage}, e as subscriptions emitem {@code PostView} — tudo isso acontece <b>antes</b> de haver
 * um resolver. Se estes tipos morassem em {@code interfaces}, a aplicação importaria a apresentação, que é
 * a seta ao contrário.
 * <p>
 * Não é um read model separado: o estado gravado é a própria entidade {@code Post}. Este record existe
 * para atravessar a fronteira com uma forma estável, sem arrastar JPA nem Axon junto.
 *
 * <h2>A dívida que fica</h2>
 * {@code PostView} carrega {@code @Name("Post")}, {@code @Id} e {@code @Description} do MicroProfile
 * GraphQL — é o que nomeia o tipo no schema, e é a aplicação sabendo de protocolo. Separar isso significa
 * duas views e um mapper a mais por agregado; a POC escolheu não pagar. Se pagar um dia, a fronteira a
 * mexer é o {@code PostViewMapper}.
 */
package dev.manuelantunes.axonposts.application.post.view;
