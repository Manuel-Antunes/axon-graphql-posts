package dev.manuelantunes.axonposts.interfaces.graphql;

import dev.manuelantunes.axonposts.domain.post.PostRepository;
import dev.manuelantunes.axonposts.domain.post.vo.PostId;
import dev.manuelantunes.axonposts.domain.tag.Tag;
import dev.manuelantunes.axonposts.dto.controller.PostView;
import dev.manuelantunes.axonposts.dto.controller.TagView;
import dev.manuelantunes.axonposts.mapper.PostViewMapper;
import graphql.schema.DataFetchingEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dataloader.DataLoader;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.data.query.ScrollSubrange;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * O campo {@code Post.tags}: uma cursor connection servida por <b>DataLoader</b>.
 *
 * <h2>O N+1 que isto resolve</h2>
 * Sem lote, uma query {@code posts(first: 20) { edges { node { tags { … } } } }} dispara 21 consultas: uma
 * para os posts e uma para as tags de cada um. Com o DataLoader, o graphql-java junta os 20 pedidos de
 * {@code tags} do mesmo nível de execução e chama a função de lote <b>uma vez</b>, com os 20 ids —
 * {@code PostRepository.findTagsByPostIds} resolve tudo num {@code join fetch} só.
 *
 * <h2>Por que {@code @SchemaMapping} + DataLoader, e não {@code @BatchMapping}</h2>
 * {@code @BatchMapping} é açúcar para exatamente o que esta classe faz à mão — o javadoc dele diz isso:
 * registrar a função no {@link BatchLoaderRegistry} e expor um {@code DataFetcher} que consulta o
 * {@code DataLoader}. O que ele <b>não</b> faz é enxergar argumentos de campo: um método
 * {@code @BatchMapping} só recebe a coleção de chaves, {@code @ContextValue}, {@code GraphQLContext} e
 * {@code BatchLoaderEnvironment} — não há {@code @Argument} nem {@link ScrollSubrange}.
 * <p>
 * Como {@code tags} é paginado ({@code first}/{@code after}), a forma anotada não dá conta. A escolha
 * então é entre um campo sem paginação e a forma explícita; esta classe usa a explícita, que é o mesmo
 * DataLoader com o argumento na mão. Se um dia {@code tags} deixar de ser paginado, o método vira um
 * {@code @BatchMapping} de três linhas.
 *
 * <h2>Recorte em memória, e quando isso deixaria de servir</h2>
 * O lote traz todas as tags de cada post e a paginação recorta em memória. É o certo para uma coleção
 * filha pequena: ela já veio inteira, e paginar no banco por post desfaria o lote. Para uma coleção
 * grande, o caminho seria uma consulta com janela por chave (window function) dentro da própria função de
 * lote — a fronteira desta classe não mudaria.
 */
@Controller
public class PostTagsController {

    private static final Logger log = LoggerFactory.getLogger(PostTagsController.class);

    /**
     * Nome do DataLoader no registry. O {@code DataLoaderMethodArgumentResolver} do Spring resolveria um
     * parâmetro {@code DataLoader<K, V>} pelo nome da <i>classe do valor</i> — que aqui seria
     * {@code java.util.List}, não este loader. Por isso o loader é buscado pelo nome, explicitamente.
     */
    private static final String LOADER = "Post.tags";

    /** Página usada quando o cliente não manda {@code first}. */
    static final int DEFAULT_PAGE_SIZE = 20;

    private final PostRepository posts;
    private final PostViewMapper viewMapper;

    public PostTagsController(PostRepository posts, PostViewMapper viewMapper, BatchLoaderRegistry registry) {
        this.posts = posts;
        this.viewMapper = viewMapper;

        registry.<String, List<TagView>>forName(LOADER).registerMappedBatchLoader(
                (postIds, environment) ->
                        Mono.fromCallable(() -> loadTagsOf(postIds))
                                // a função de lote é chamada no event-loop, e lá dentro tem JPA bloqueante
                                .subscribeOn(Schedulers.boundedElastic())
        );
    }

    /**
     * A função de lote: recebe os ids de <b>todos</b> os posts que pediram tags nesta resposta e devolve
     * um mapa id → tags. Uma consulta, não uma por post.
     */
    private Map<String, List<TagView>> loadTagsOf(Collection<String> postIds) {
        // é esta linha que prova o lote: uma chamada por resposta GraphQL, não uma por post
        log.debug("lote de tags: {} post(s) numa consulta", postIds.size());

        Map<PostId, List<Tag>> byPost =
                posts.findTagsByPostIds(postIds.stream().map(PostId::of).toList());

        return byPost.entrySet().stream().collect(Collectors.toMap(
                entry -> entry.getKey().value(),
                entry -> viewMapper.toTagViews(entry.getValue()),
                (first, second) -> first
        ));
    }

    /**
     * O {@code DataLoader} devolve {@code null} para um post sem nenhuma tag (a chave não veio no mapa) —
     * daí o {@link Function} que o troca por lista vazia antes de recortar.
     */
    @SchemaMapping(typeName = "Post", field = "tags")
    public CompletableFuture<Window<TagView>> tags(PostView post,
                                                   ScrollSubrange subrange,
                                                   DataFetchingEnvironment environment) {
        long offset = Connections.startOffset(subrange);
        int limit = subrange.count().orElse(DEFAULT_PAGE_SIZE);

        DataLoader<String, List<TagView>> loader = environment.getDataLoader(LOADER);
        return loader.load(post.id())
                .thenApply(tags -> Connections.slice(tags == null ? List.of() : tags, offset, limit));
    }
}
