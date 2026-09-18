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

/**
 * Vários posts por id, numa consulta só.
 *
 * <h2>Por que não basta o {@code FindPost}</h2>
 * A federação nunca pede uma entidade de cada vez: o {@code _entities} chega com a lista inteira de
 * representações que o roteador precisa daquele plano de query. Resolver com {@code FindPost} num laço
 * seria o N+1 de sempre, só que atravessando o roteador — exatamente o que
 * {@link FindPostsByAuthorIdsQuery} e {@link FindTagsByPostIdsQuery} já evitam dentro do schema.
 *
 * <h2>Mapa, e não lista</h2>
 * O resultado é indexado por id porque quem chama precisa devolver <b>uma posição por representação</b>,
 * na ordem recebida e com {@code null} onde o post não existe. Uma lista obrigaria o chamador a casar as
 * duas ordens à mão; o mapa deixa isso ser uma projeção direta da lista de ids pedida.
 */
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
