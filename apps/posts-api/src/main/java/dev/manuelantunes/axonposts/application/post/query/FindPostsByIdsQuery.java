package dev.manuelantunes.axonposts.application.post.query;

import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.post.view.PostViewMapper;
import org.axonframework.messaging.queryhandling.annotation.Query;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@ApplicationScoped
public class FindPostsByIdsQuery {
    @Query(namespace = "posts", name = "FindPostsByIds", version = "1.0.0")
    public record FindPostsByIds(List<String> postIds) {
    }

    public record PostsById(Map<String, PostView> byId) {
    }

    private final PostRepository posts;
    private final PostViewMapper viewMapper;

    public FindPostsByIdsQuery(PostRepository posts, PostViewMapper viewMapper) {
        this.posts = posts;
        this.viewMapper = viewMapper;
    }

    @QueryHandler
    public PostsById handle(FindPostsByIds query) {
        if (query.postIds().isEmpty()) {
            return new PostsById(Map.of());
        }
        return new PostsById(posts
                .findAllById(query.postIds().stream().map(PostId::of).toList())
                .stream()
                .map(viewMapper::toView)
                .collect(Collectors.toMap(PostView::id, Function.identity(), (first, second) -> first)));
    }
}
