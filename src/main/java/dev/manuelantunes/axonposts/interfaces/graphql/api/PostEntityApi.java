package dev.manuelantunes.axonposts.interfaces.graphql.api;

import java.util.List;

import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.jboss.logging.Logger;

import dev.manuelantunes.axonposts.application.post.query.FindPostsByIdsQuery.FindPostsByIds;
import dev.manuelantunes.axonposts.application.post.query.FindPostsByIdsQuery.PostsById;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import io.smallrye.graphql.api.federation.Resolver;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * O resolvedor de referência de {@code Post}: como o roteador reencontra um post que outro subgraph só
 * conhece pela chave.
 *
 * <h2>{@code @Resolver} não é {@code @Query}</h2>
 * O método serve o campo {@code _entities} e <b>não aparece no schema</b>: ele entra num tipo sintético
 * {@code Resolver} que o SmallRye monta à parte e não acrescenta à API. É por isso que a federação não
 * obriga a publicar uma query por entidade — {@code Query.post(id:)} continua sendo o que é, uma
 * operação de cliente, e não um detalhe do protocolo.
 *
 * <h2>Como o SmallRye escolhe este método</h2>
 * Não é pelo nome. Cada representação que chega em {@code _entities} vira um par
 * <i>(tipo, conjunto de nomes de argumento)</i> — {@code ("Post", {"id"})} — e o
 * {@code FederationDataFetcher} procura um campo que devolva {@code Post} e cujos argumentos sejam
 * <b>exatamente</b> esse conjunto. Daí o {@code @Name("id")} explícito: o nome do argumento é o que casa
 * com o {@code @Key(fields = "id")} do {@link PostView}, e um {@code postId} aqui deixaria o
 * {@code _entities} sem resolvedor — em runtime, não na compilação.
 *
 * <h2>Lote, e por que a lista é nula nas pontas</h2>
 * Com {@code federation.batch-resolving-enabled} o buscador procura primeiro um campo que devolva
 * <b>lista</b> do tipo e entrega todas as representações de uma vez; sem ele, chamaria um método por
 * representação. É o mesmo N+1 de {@code Post.author} e {@code Post.tags}, agora entre subgraphs.
 * <p>
 * O contrato do lote é rígido e verificado em runtime: <b>uma posição por representação, na ordem em que
 * chegaram</b>. O {@code executeList} compara os tamanhos e estoura se divergirem. Por isso o resultado
 * é uma projeção da lista de ids pedida sobre o mapa, e não a lista que veio do banco: um id que não
 * existe mais precisa virar {@code null} <i>naquela</i> posição, que é o que a especificação manda
 * responder para uma entidade desaparecida.
 * <p>
 * E é também por isso que o elemento da lista <b>não</b> leva {@code @NonNull}: com {@code [Post!]} o
 * casamento por tipo de retorno falha — o {@code FederationDataFetcher} desembrulha a lista e espera um
 * tipo <i>nomeado</i>, não um {@code NonNull} — e o lote silenciosamente deixa de ser usado.
 *
 * <h2>{@code @Id} no argumento em lote <b>não</b> pode estar aqui</h2>
 * O argumento é {@code List<String>} sem {@code @Id}, e a ausência é deliberada. O
 * {@code ReferenceCreator} do SmallRye testa {@code @Id} <b>antes</b> de desembrulhar a coleção: com a
 * anotação, ele pede um scalar {@code ID} para {@code java.util.List} e o tipo esperado do argumento
 * deixa de ser {@code String}. Na chamada, cada id vira um "String onde se esperava um objeto" e o
 * SmallRye tenta lê-lo como JSON — {@code JsonbException: Unexpected char}.
 * <p>
 * E quase não se vê: o erro vira um {@code DataFetcherResult} sem dados, que o
 * {@code FederationDataFetcher} descarta, e o que chega ao cliente é um
 * {@code NullPointerException: resultList is null} sem nenhuma pista do argumento. O tipo do argumento
 * no schema é indiferente — o tipo {@code Resolver} não é publicado e o {@code _entities} entrega os
 * valores crus, sem coerção. Quem pega isto é o {@code FederationEntitiesE2ETest}, e só ele.
 *
 * <h2>O offload continua sendo daqui</h2>
 * {@code @Blocking} e companhia não podem ser combinados com {@code @Resolver}; este projeto nunca
 * dependeu delas, porque o salto para fora do event-loop é escrito no corpo do método
 * ({@code runSubscriptionOn}), como em todo resolver daqui. A limitação da extensão não toca no que
 * já existia.
 */
@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class PostEntityApi {

    private static final Logger log = Logger.getLogger(PostEntityApi.class);

    private final QueryGateway queryGateway;

    public PostEntityApi(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    @Resolver
    public Uni<List<PostView>> post(@Name("id") List<String> id) {
        log.debugf("_entities: lote de %d post(s) numa consulta", id.size());
        return Uni.createFrom()
                .completionStage(() -> queryGateway.query(new FindPostsByIds(id), PostsById.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(PostsById::byId)
                .map(byId -> id.stream().map(byId::get).toList());
    }
}
