package dev.manuelantunes.axonposts.interfaces.graphql.api;

import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Id;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Query;
import org.axonframework.messaging.queryhandling.gateway.QueryGateway;

import dev.manuelantunes.axonposts.application.post.query.FindAllPostsQuery.FindAllPosts;
import dev.manuelantunes.axonposts.application.post.query.FindPostQuery.FindPost;
import dev.manuelantunes.axonposts.application.post.view.PostPage;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.ConnectionArgs;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.Connections;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.PostConnection;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.PostEdge;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Camada de interface das <b>queries</b> GraphQL: traduz a operação GraphQL numa query do Axon e nada
 * mais. Sem regra, sem acesso a banco, sem conhecer o domínio — é adaptador de protocolo.
 *
 * <h2>Cursor connection, e o que mudou em relação ao Spring</h2>
 * O Spring GraphQL entrega a connection pronta: {@code ScrollSubrange} de entrada, {@code Window<T>} de
 * saída, e um {@code ConnectionTypeDefinitionConfigurer} que <b>gera</b> {@code PostConnection},
 * {@code PostEdge} e {@code PageInfo} no schema a partir do sufixo do campo. Nada disso existe no
 * SmallRye.
 * <p>
 * O que existe aqui é o pacote {@code interfaces.graphql.relay}, escrito uma vez: {@link ConnectionArgs} valida e
 * traduz {@code first}/{@code after} para {@code offset}/{@code limit}, {@link Connections} monta edges e
 * {@code pageInfo}, e {@link PostConnection}/{@link PostEdge} — duas linhas — dão os nomes que a
 * convenção Relay pede. O resolver continua tendo três linhas, e agora dá para ler o que acontece entre
 * o cursor e o SQL.
 * <p>
 * Há um ganho de contrato junto: o teto de {@link ConnectionArgs#MAX_LIMIT} itens por página. O
 * {@code ScrollSubrange} não tinha um, e {@code posts(first: 100000)} descia até o {@code Limit} do
 * Spring Data.
 *
 * <h2>Threading</h2>
 * O {@code SimpleQueryBus} executa o {@code @QueryHandler} na thread que despacha, e lá dentro tem JPA
 * bloqueante. Um resolver que devolve {@code Uni} roda no event-loop do Vert.x, então o despacho vai para
 * o worker pool: {@code completionStage(Supplier)} + {@code runSubscriptionOn}. O {@code Supplier} é o
 * que decide — ver o {@code package-info} desta camada.
 */
@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class PostQueryApi {

    /** Prefixo do cursor desta conexão. Trocá-lo invalida todos os cursores já entregues. */
    static final String CURSOR_TYPE = "post";

    private final QueryGateway queryGateway;

    public PostQueryApi(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    /** {@code Uni} vazio (null no GraphQL) quando o Post não existe. */
    @Query("post")
    @Description("Um Post pelo id; null se não existir")
    public Uni<PostView> post(@Name("id") @Id @NonNull String id) {
        return Uni.createFrom()
                .completionStage(() -> queryGateway.query(new FindPost(id), PostView.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    /**
     * {@code posts(first: Int, after: String): PostConnection!}
     * <p>
     * Paginação só para frente: é o que o read model, ordenado por criação, comporta.
     *
     * @throws dev.manuelantunes.axonposts.interfaces.graphql.error.BadRequestException se {@code first} estourar o
     *         teto, ou se {@code after} não for um cursor desta conexão
     */
    @Query("posts")
    @NonNull
    @Description("Posts em ordem de criação, como Relay cursor connection")
    public Uni<PostConnection> posts(
            @Name("first") @Description("Quantos posts trazer; ausente = 20") Integer first,
            @Name("after") @Description("Cursor do último post já visto; ausente = do começo") String after) {

        ConnectionArgs args = ConnectionArgs.of(CURSOR_TYPE, first, after);

        return Uni.createFrom()
                .completionStage(() -> queryGateway.query(
                        new FindAllPosts(args.offset(), args.limit()), PostPage.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(page -> Connections.page(
                        page.items(), page.hasNext(), args, PostEdge::new, PostConnection::new));
    }
}
