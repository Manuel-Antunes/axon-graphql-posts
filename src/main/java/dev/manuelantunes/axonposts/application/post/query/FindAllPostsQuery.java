package dev.manuelantunes.axonposts.application.post.query;

/**
 * Query: uma página de Posts, em ordem de criação.
 * <p>
 * Paginação em {@code offset}/{@code limit} crus: a query é uma mensagem, e mensagem não carrega tipo de
 * framework. Quem transforma o cursor do GraphQL nestes dois números é o controller.
 *
 * @param offset índice da primeira linha desejada, contando de 0
 * @param limit  quantidade máxima de linhas na página
 */
@org.axonframework.messaging.queryhandling.annotation.Query(
        namespace = "posts", name = "FindAllPosts", version = "1.0.0"
)
public record FindAllPostsQuery(long offset, int limit) {
}
