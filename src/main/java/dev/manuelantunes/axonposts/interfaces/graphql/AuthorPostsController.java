package dev.manuelantunes.axonposts.interfaces.graphql;

import dev.manuelantunes.axonposts.application.post.query.FindPostsByAuthorIdsQuery.FindPostsByAuthorIds;
import dev.manuelantunes.axonposts.application.post.query.FindPostsByAuthorIdsQuery.PostsByAuthor;
import dev.manuelantunes.axonposts.dto.controller.AuthorView;
import dev.manuelantunes.axonposts.dto.controller.PostView;
import graphql.schema.DataFetchingEnvironment;
import org.axonframework.extension.reactor.messaging.queryhandling.gateway.ReactorQueryGateway;
import org.dataloader.DataLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.method.annotation.SchemaMapping;
import org.springframework.graphql.data.query.ScrollSubrange;
import org.springframework.graphql.execution.BatchLoaderRegistry;
import org.springframework.stereotype.Controller;
import reactor.core.scheduler.Schedulers;

import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * O campo {@code Author.posts}: cursor connection servida por <b>DataLoader</b>, gêmea do
 * {@code PostTagsController}.
 *
 * <h2>Por que a forma explícita de novo</h2>
 * Pela mesma razão de lá: o campo tem argumentos ({@code first}/{@code after}) e um
 * {@code @BatchMapping} não os enxerga. Compare com o {@code UserFieldsController}, ao lado — lá os
 * campos são escalares sem argumento e a forma anotada resolve em três linhas. A escolha entre as duas
 * não é de gosto: é ter argumento ou não ter.
 *
 * <h2>O N+1 que isto resolve</h2>
 * {@code posts(first: 20) { edges { node { author { posts { … } } } } }} pediria os posts de 20 autores.
 * Com o lote, os ids distintos vão numa consulta só.
 *
 * <h2>Recorte em memória: aqui o limite é mais perto do que nas tags</h2>
 * As tags de um post são poucas por natureza. Os posts de um autor <b>não</b> são: um autor produtivo
 * acumula milhares, e trazer todos para devolver os 20 primeiros é desperdício que cresce com o tempo.
 * <p>
 * A troca é consciente e vale enquanto o volume for de POC. O caminho de saída não muda a fronteira
 * desta classe: seria uma consulta com {@code row_number() over (partition by author_id order by
 * created_at desc)} dentro da própria função de lote, devolvendo já recortado — o {@code @SchemaMapping}
 * continuaria idêntico.
 */
@Controller
public class AuthorPostsController {

    private static final Logger log = LoggerFactory.getLogger(AuthorPostsController.class);

    /** Buscado pelo nome porque o valor do loader é {@code List}, não um tipo próprio. */
    private static final String LOADER = "Author.posts";

    static final int DEFAULT_PAGE_SIZE = 20;

    // o gateway vem do registry de componentes do Axon, não de um @Bean: a inspeção do IDE não o vê
    @SuppressWarnings("SpringJavaInjectionPointsAutowiringInspection")
    public AuthorPostsController(ReactorQueryGateway queryGateway, BatchLoaderRegistry registry) {
        registry.<String, List<PostView>>forName(LOADER).registerMappedBatchLoader(
                (authorIds, environment) -> {
                    log.debug("lote de posts por autor: {} autor(es) numa consulta", authorIds.size());
                    return queryGateway
                            .query(new FindPostsByAuthorIds(List.copyOf(authorIds)), PostsByAuthor.class)
                            .map(PostsByAuthor::byAuthorId)
                            .subscribeOn(Schedulers.boundedElastic());
                });
    }

    /** Autor sem post nenhum não vem no mapa do loader — daí o {@code null} virar lista vazia. */
    @SchemaMapping(typeName = "Author", field = "posts")
    public CompletableFuture<Window<PostView>> posts(AuthorView author,
                                                     ScrollSubrange subrange,
                                                     DataFetchingEnvironment environment) {
        long offset = Connections.startOffset(subrange);
        int limit = subrange.count().orElse(DEFAULT_PAGE_SIZE);

        DataLoader<String, List<PostView>> loader = environment.getDataLoader(LOADER);
        return loader.load(author.id())
                .thenApply(found -> Connections.slice(found == null ? List.of() : found, offset, limit));
    }
}
