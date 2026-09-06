package dev.manuelantunes.axonposts.interfaces.graphql;

import dev.manuelantunes.axonposts.application.post.PostPage;
import dev.manuelantunes.axonposts.application.post.PostView;
import dev.manuelantunes.axonposts.application.post.query.FindAllPostsQuery;
import dev.manuelantunes.axonposts.application.post.query.FindPostQuery;
import org.axonframework.extension.reactor.messaging.queryhandling.gateway.ReactorQueryGateway;
import org.springframework.data.domain.OffsetScrollPosition;
import org.springframework.data.domain.ScrollPosition;
import org.springframework.data.domain.Window;
import org.springframework.graphql.data.query.ScrollSubrange;
import org.springframework.graphql.data.method.annotation.Argument;
import org.springframework.graphql.data.method.annotation.QueryMapping;
import org.springframework.stereotype.Controller;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Camada de interface das <b>queries</b> GraphQL: traduz a operação GraphQL numa query do Axon e nada
 * mais. Sem regra, sem acesso a banco, sem conhecer o domínio — é adaptador de protocolo.
 *
 * <h2>Cursor connection nativa do Spring</h2>
 * O campo {@code posts} é uma Relay connection montada pelo Spring GraphQL, sem nenhum tipo de connection
 * escrito à mão. As três peças, todas autoconfiguradas pelo Boot por causa do Spring Data no classpath:
 * <ul>
 *   <li>{@link ScrollSubrange} como parâmetro: o {@code ScrollSubrangeMethodArgumentResolver} lê os
 *       argumentos {@code first}/{@code after} do campo e decodifica o cursor num
 *       {@link ScrollPosition}, usando o {@code CursorStrategy<ScrollPosition>};</li>
 *   <li>{@link Window} como retorno: o {@code WindowConnectionAdapter} o converte em {@code edges},
 *       {@code cursor} por item e {@code pageInfo};</li>
 *   <li>{@code ConnectionTypeDefinitionConfigurer}: gera os tipos {@code PostConnection},
 *       {@code PostEdge} e {@code PageInfo} no schema, a partir do sufixo do nome.</li>
 * </ul>
 * O que sobra para este controller é a tradução entre o cursor (opaco) e o par {@code offset}/{@code limit}
 * que a query do Axon entende.
 *
 * <h2>Threading</h2>
 * O {@code SimpleQueryBus} executa o {@code @QueryHandler} na thread que despacha, e lá dentro tem JPA
 * bloqueante — por isso o {@code subscribeOn(boundedElastic)}, para não segurar o event-loop do Netty.
 */
@Controller
public class PostQueryController {

    /** Página usada quando o cliente não manda {@code first}. */
    static final int DEFAULT_PAGE_SIZE = 20;

    private final ReactorQueryGateway queryGateway;

    public PostQueryController(ReactorQueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    /** Mono vazio (null no GraphQL) quando o Post não existe. */
    @QueryMapping
    public Mono<PostView> post(@Argument String id) {
        return queryGateway.query(new FindPostQuery(id), PostView.class)
                .subscribeOn(Schedulers.boundedElastic());
    }

    @QueryMapping
    public Mono<Window<PostView>> posts(ScrollSubrange subrange) {
        long offset = startOffset(subrange);
        int limit = subrange.count().orElse(DEFAULT_PAGE_SIZE);

        return queryGateway.query(new FindAllPostsQuery(offset, limit), PostPage.class)
                .map(PostQueryController::toWindow)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Cursor → índice da primeira linha. Um {@link OffsetScrollPosition} aponta para a <b>última linha
     * já vista</b>, então a próxima página começa em {@code offset + 1}; sem cursor, começa em 0.
     */
    private static long startOffset(ScrollSubrange subrange) {
        return subrange.position()
                .filter(OffsetScrollPosition.class::isInstance)
                .map(OffsetScrollPosition.class::cast)
                .filter(position -> !position.isInitial())
                .map(position -> position.getOffset() + 1)
                .orElse(0L);
    }

    /**
     * O {@code positionFunction} é o que dá cursor a cada item: o item de índice {@code i} da página
     * recebe a posição {@code offset + i}, que é o que o cliente devolve como {@code after}.
     */
    private static Window<PostView> toWindow(PostPage page) {
        return Window.from(
                page.items(),
                OffsetScrollPosition.positionFunction(page.offset()),
                page.hasNext()
        );
    }
}
