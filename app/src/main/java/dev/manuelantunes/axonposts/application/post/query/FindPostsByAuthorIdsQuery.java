package dev.manuelantunes.axonposts.application.post.query;

import dev.manuelantunes.axonposts.domain.post.Post;
import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.user.vo.UserId;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.post.view.PostViewMapper;
import org.axonframework.messaging.queryhandling.annotation.Query;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A query <b>FindPostsByAuthorIds</b>: os posts de vários autores de uma vez, mais recentes primeiro.
 * <p>
 * O par do DataLoader do campo {@code Author.posts}, pelo mesmo motivo do {@code FindTagsByPostIds}: uma
 * resposta com N autores não pode virar N consultas, e o acesso a dados pertence à aplicação e não ao
 * controller.
 */
@ApplicationScoped
public class FindPostsByAuthorIdsQuery {

    @Query(namespace = "posts", name = "FindPostsByAuthorIds", version = "1.0.0")
    public record FindPostsByAuthorIds(List<String> authorIds) {
    }

    /** @param byAuthorId autor → posts, mais recentes primeiro. Autores sem post não aparecem */
    public record PostsByAuthor(Map<String, List<PostView>> byAuthorId) {
    }

    private final PostRepository posts;
    private final PostViewMapper viewMapper;

    public FindPostsByAuthorIdsQuery(PostRepository posts, PostViewMapper viewMapper) {
        this.posts = posts;
        this.viewMapper = viewMapper;
    }

    @QueryHandler
    public PostsByAuthor handle(FindPostsByAuthorIds query) {
        if (query.authorIds().isEmpty()) {
            return new PostsByAuthor(Map.of());
        }

        Map<UserId, List<Post>> byAuthor =
                posts.findByAuthorIds(query.authorIds().stream().map(UserId::of).toList());

        return new PostsByAuthor(byAuthor.entrySet().stream().collect(Collectors.toMap(
                entry -> entry.getKey().value(),
                entry -> entry.getValue().stream().map(viewMapper::toView).toList(),
                (first, second) -> first)));
    }
}
