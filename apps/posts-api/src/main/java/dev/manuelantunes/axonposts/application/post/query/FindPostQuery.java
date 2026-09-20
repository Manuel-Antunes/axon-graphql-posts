package dev.manuelantunes.axonposts.application.post.query;

import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.post.view.PostViewMapper;
import org.axonframework.messaging.queryhandling.annotation.Query;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

@ApplicationScoped
public class FindPostQuery {
    @Query(namespace = "posts", name = "FindPost", version = "1.0.0")
    public record FindPost(String postId) {
    }

    private final PostRepository posts;
    private final PostViewMapper viewMapper;

    public FindPostQuery(PostRepository posts, PostViewMapper viewMapper) {
        this.posts = posts;
        this.viewMapper = viewMapper;
    }

    @QueryHandler
    public Optional<PostView> handle(FindPost query) {
        return posts.findById(PostId.of(query.postId())).map(viewMapper::toView);
    }
}
