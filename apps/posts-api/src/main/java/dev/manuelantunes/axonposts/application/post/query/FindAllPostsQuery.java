package dev.manuelantunes.axonposts.application.post.query;

import dev.manuelantunes.axonposts.application.post.view.PostPage;
import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.post.view.PostViewMapper;
import org.axonframework.messaging.queryhandling.annotation.Query;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;

@ApplicationScoped
public class FindAllPostsQuery {
    @Query(namespace = "posts", name = "FindAllPosts", version = "1.0.0")
    public record FindAllPosts(long offset, int limit) {
    }

    private final PostRepository posts;
    private final PostViewMapper viewMapper;

    public FindAllPostsQuery(PostRepository posts, PostViewMapper viewMapper) {
        this.posts = posts;
        this.viewMapper = viewMapper;
    }

    @QueryHandler
    public PostPage handle(FindAllPosts query) {
        int limit = query.limit();
        List<Post> rows = posts.findAll(query.offset(), limit + 1);

        boolean hasNext = rows.size() > limit;
        List<PostView> items = (hasNext ? rows.subList(0, limit) : rows).stream()
                .map(viewMapper::toView)
                .toList();

        return new PostPage(items, query.offset(), hasNext);
    }
}
