package dev.manuelantunes.axonposts.application.tag.query;

import dev.manuelantunes.axonposts.domain.tag.TagRepository;
import dev.manuelantunes.axonposts.domain.tag.vo.TagId;
import dev.manuelantunes.axonposts.application.post.view.PostViewMapper;
import dev.manuelantunes.axonposts.application.tag.view.TagView;
import org.axonframework.messaging.queryhandling.annotation.Query;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;

import jakarta.enterprise.context.ApplicationScoped;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Várias tags por id, numa consulta só.
 *
 * <h2>Por que a Tag ganhou consulta por id agora</h2>
 * Dentro deste schema a tag só é alcançada a partir de um post, e
 * {@link dev.manuelantunes.axonposts.application.post.query.FindTagsByPostIdsQuery} dá conta disso. A
 * federação inverte o sentido: um subgraph vizinho que guarde, digamos, estatísticas por tag referencia
 * {@code Tag} pela chave e o roteador volta aqui pedindo {@code _entities} — sem passar por post nenhum.
 * É por isso que a tag precisa ser resolvível sozinha, e é o que a torna uma entidade de verdade.
 */
@ApplicationScoped
public class FindTagsByIdsQuery {

    @Query(namespace = "tags", name = "FindTagsByIds", version = "1.0.0")
    public record FindTagsByIds(List<String> tagIds) {
    }

    public record TagsById(Map<String, TagView> byId) {
    }

    private final TagRepository tags;
    private final PostViewMapper viewMapper;

    public FindTagsByIdsQuery(TagRepository tags, PostViewMapper viewMapper) {
        this.tags = tags;
        this.viewMapper = viewMapper;
    }

    @QueryHandler
    public TagsById handle(FindTagsByIds query) {
        if (query.tagIds().isEmpty()) {
            return new TagsById(Map.of());
        }
        return new TagsById(tags
                .findAllById(query.tagIds().stream().map(TagId::of).toList())
                .stream()
                .map(viewMapper::toTagView)
                .collect(Collectors.toMap(TagView::id, Function.identity(), (first, second) -> first)));
    }
}
