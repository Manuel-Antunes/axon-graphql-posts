package dev.manuelantunes.axonposts.application.post.query;

import dev.manuelantunes.axonposts.application.post.PostView;
import dev.manuelantunes.axonposts.application.post.port.PostReadRepository;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Handler de <b>uma</b> query: {@link FindPostQuery}.
 * <p>
 * O lado da leitura nunca toca no domínio: não carrega entidade, não reidrata stream, não conhece
 * {@code Post}. Vai direto ao read model pela porta {@link PostReadRepository} — é essa assimetria que o
 * CQRS compra.
 * <p>
 * {@code Optional} vazio vira resposta vazia (o {@code Mono} do gateway completa sem valor), que o
 * GraphQL serializa como {@code null}.
 */
@Component
public class FindPostQueryHandler {

    private final PostReadRepository posts;

    public FindPostQueryHandler(PostReadRepository posts) {
        this.posts = posts;
    }

    @QueryHandler
    public Optional<PostView> handle(FindPostQuery query) {
        return posts.findById(query.postId());
    }
}
