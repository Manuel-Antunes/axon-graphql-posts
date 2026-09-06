package dev.manuelantunes.axonposts.application.post.query;

import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.dto.controller.PostView;
import dev.manuelantunes.axonposts.mapper.PostViewMapper;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Handler de <b>uma</b> query: {@link FindPostQuery}.
 * <p>
 * Lê a entidade de domínio pela porta e a achata em {@link PostView} na saída. O achatamento acontece
 * aqui, e não no controller, para que o que trafega no query bus (e nas subscriptions) seja sempre o
 * mesmo DTO estável — e não uma entidade JPA com coleções gerenciadas.
 * <p>
 * Um id que não existe simplesmente não acha nada: {@code Optional} vazio vira resposta vazia (o
 * {@code Mono} do gateway completa sem valor), que o GraphQL serializa como {@code null}.
 */
@Component
public class FindPostQueryHandler {

    private final PostRepository posts;
    private final PostViewMapper viewMapper;

    public FindPostQueryHandler(PostRepository posts, PostViewMapper viewMapper) {
        this.posts = posts;
        this.viewMapper = viewMapper;
    }

    @QueryHandler
    public Optional<PostView> handle(FindPostQuery query) {
        return posts.findById(PostId.of(query.postId())).map(viewMapper::toView);
    }
}
