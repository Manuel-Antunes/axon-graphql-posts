package dev.manuelantunes.axonposts.application.post.query;

import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.application.tag.view.TagView;
import dev.manuelantunes.axonposts.application.post.view.PostViewMapper;
import org.axonframework.messaging.queryhandling.annotation.Query;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@ApplicationScoped
public class FindTagsByPostIdsQuery {
    @Query(namespace = "posts", name = "FindTagsByPostIds", version = "1.0.0")
    public record FindTagsByPostIds(List<String> postIds) {
    }

    public record TagsByPost(Map<String, List<TagView>> byPostId) {
    }

    private final PostRepository posts;
    private final PostViewMapper viewMapper;

    public FindTagsByPostIdsQuery(PostRepository posts, PostViewMapper viewMapper) {
        this.posts = posts;
        this.viewMapper = viewMapper;
    }

    @QueryHandler
    public TagsByPost handle(FindTagsByPostIds query) {
        if (query.postIds().isEmpty()) {
            return new TagsByPost(Map.of());
        }

        Map<PostId, List<Tag>> byPost =
                posts.findTagsByPostIds(query.postIds().stream().map(PostId::of).toList());

        return new TagsByPost(byPost.entrySet().stream().collect(Collectors.toMap(
                entry -> entry.getKey().value(),
                entry -> viewMapper.toTagViews(entry.getValue()),
                (first, second) -> first)));
    }
}
