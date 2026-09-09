package dev.manuelantunes.axonposts.application.post.query;

import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.dto.controller.TagView;
import dev.manuelantunes.axonposts.mapper.PostViewMapper;
import org.axonframework.messaging.queryhandling.annotation.Query;
import org.axonframework.messaging.queryhandling.annotation.QueryHandler;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * A query <b>FindTagsByPostIds</b>: as tags de vários posts de uma vez.
 *
 * <h2>Por que uma query, e não o repositório no controller</h2>
 * O {@code PostTagsController} chamava {@code PostRepository} diretamente. Funcionava, mas era o único
 * caminho de leitura do sistema que pulava a camada de aplicação — todos os outros passam pelo query bus.
 * A inconsistência custa mais do que a indireção: quem lê o controller precisa descobrir que ali existe
 * uma regra de acesso a dados que não está onde as outras estão.
 * <p>
 * Com a query, o achatamento para {@link TagView} também volta a acontecer na aplicação, como no
 * {@code FindPostQuery} — o que trafega para a apresentação é sempre DTO, nunca entidade gerenciada.
 *
 * <h2>Feita para ser chamada em lote</h2>
 * Recebe <b>todos</b> os ids de uma resposta GraphQL e responde com um mapa. É o par do DataLoader: sem
 * ela, uma resposta com N posts faria N consultas.
 */
@Component
public class FindTagsByPostIdsQuery {

    /** A mensagem: as tags destes posts. Ids como texto — uma query não rejeita id malformado. */
    @Query(namespace = "posts", name = "FindTagsByPostIds", version = "1.0.0")
    public record FindTagsByPostIds(List<String> postIds) {
    }

    /**
     * A resposta, num record em vez de um {@code Map} solto: tipo de resposta do query bus com genéricos
     * aninhados é onde a inferência do Axon fica frágil, e um record nomeado documenta o que é a chave.
     *
     * @param byPostId post → tags, em ordem. Posts sem tag não aparecem
     */
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
