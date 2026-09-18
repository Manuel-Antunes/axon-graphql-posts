package dev.manuelantunes.axonposts.application.post.query;

import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.post.view.PostViewMapper;
import org.axonframework.messaging.queryhandling.annotation.Query;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import jakarta.enterprise.context.ApplicationScoped;

import java.util.Optional;

/**
 * A query <b>FindPost</b>: a mensagem {@link FindPost} e como ela é respondida.
 * <p>
 * Lê a entidade de domínio pela porta e a achata em {@link PostView} na saída. O achatamento acontece
 * aqui, e não no controller, para que o que trafega no query bus (e nas subscriptions) seja sempre o
 * mesmo DTO estável — e não uma entidade JPA com coleções gerenciadas.
 * <p>
 * Um id que não existe simplesmente não acha nada: {@code Optional} vazio vira resposta vazia (o
 * {@code Mono} do gateway completa sem valor), que o GraphQL serializa como {@code null}.
 */
@ApplicationScoped
public class FindPostQuery {

    /**
     * A mensagem: um Post pelo id.
     * <p>
     * O id vem como {@code String} e não como {@code PostId} de propósito: uma query não decide nada,
     * então não faz sentido rejeitar um id malformado com exceção de domínio — id que não existe
     * simplesmente não acha nada.
     */
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
