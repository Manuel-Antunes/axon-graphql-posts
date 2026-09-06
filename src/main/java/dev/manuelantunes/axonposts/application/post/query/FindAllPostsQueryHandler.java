package dev.manuelantunes.axonposts.application.post.query;

import dev.manuelantunes.axonposts.application.post.PostPage;
import dev.manuelantunes.axonposts.application.post.PostView;
import dev.manuelantunes.axonposts.application.post.port.PostReadRepository;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Handler de <b>uma</b> query: {@link FindAllPostsQuery}.
 * <p>
 * A mecânica da paginação mora aqui, e não no controller nem no repositório: pede-se <b>uma linha a mais</b>
 * do que o cliente quer, e a existência dessa linha extra é a resposta para "tem próxima página?". Ela é
 * descartada antes de sair — o cliente recebe exatamente o que pediu.
 */
@Component
public class FindAllPostsQueryHandler {

    private final PostReadRepository posts;

    public FindAllPostsQueryHandler(PostReadRepository posts) {
        this.posts = posts;
    }

    @QueryHandler
    public PostPage handle(FindAllPostsQuery query) {
        int limit = query.limit();
        List<PostView> rows = posts.findAll(query.offset(), limit + 1);

        boolean hasNext = rows.size() > limit;
        List<PostView> items = hasNext ? rows.subList(0, limit) : rows;

        return new PostPage(items, query.offset(), hasNext);
    }
}
