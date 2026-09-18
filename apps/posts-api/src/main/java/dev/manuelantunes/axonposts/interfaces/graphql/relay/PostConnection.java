package dev.manuelantunes.axonposts.interfaces.graphql.relay;

import dev.manuelantunes.axonposts.application.post.view.PostView;

/**
 * {@code type PostConnection { edges: [PostEdge]!, pageInfo: PageInfo! }}.
 * <p>
 * A contraparte de {@link PostEdge}, e o tipo que os campos {@code posts} e {@code Author.posts}
 * devolvem. Ver {@link Connection} sobre por que são dois parâmetros de tipo.
 */
public final class PostConnection extends Connection<PostView, PostEdge> {
}
