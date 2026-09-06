package dev.manuelantunes.axonposts.application.post.query;

import dev.manuelantunes.axonposts.application.post.PostPage;
import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.dto.controller.PostView;
import dev.manuelantunes.axonposts.mapper.PostViewMapper;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Handler de <b>uma</b> query: {@link FindAllPostsQuery}.
 * <p>
 * A mecânica da paginação mora aqui, e não no controller nem no repositório: pede-se <b>uma linha a
 * mais</b> do que o cliente quer, e a existência dessa linha extra é a resposta para "tem próxima
 * página?". Ela é descartada antes de sair — o cliente recebe exatamente o que pediu.
 */
@Component
public class FindAllPostsQueryHandler {

    private final PostRepository posts;
    private final PostViewMapper viewMapper;

    public FindAllPostsQueryHandler(PostRepository posts, PostViewMapper viewMapper) {
        this.posts = posts;
        this.viewMapper = viewMapper;
    }

    @QueryHandler
    public PostPage handle(FindAllPostsQuery query) {
        int limit = query.limit();
        List<Post> rows = posts.findAll(query.offset(), limit + 1);

        boolean hasNext = rows.size() > limit;
        List<PostView> items = (hasNext ? rows.subList(0, limit) : rows).stream()
                .map(viewMapper::toView)
                .toList();

        return new PostPage(items, query.offset(), hasNext);
    }
}
