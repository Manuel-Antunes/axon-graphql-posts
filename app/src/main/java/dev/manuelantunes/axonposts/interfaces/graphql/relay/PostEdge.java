package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import dev.manuelantunes.axonposts.application.post.view.PostView;

/**
 * {@code type PostEdge { node: Post!, cursor: String! }}.
 * <p>
 * Uma linha, e é ela que dá nome ao tipo. Toda a mecânica — cursor, {@code pageInfo}, recorte — está em
 * {@link Edge} e em {@code Connections}, escritas uma única vez para o projeto inteiro.
 * <p>
 * Sem esta classe, o campo genérico apareceria no schema como {@code Edge_Post}. Ver {@link Edge}.
 */
public final class PostEdge extends Edge<PostView> {
}
