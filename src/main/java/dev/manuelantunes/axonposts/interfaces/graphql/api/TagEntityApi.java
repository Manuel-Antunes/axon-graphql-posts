package dev.manuelantunes.axonposts.interfaces.graphql.api;

import java.util.List;

import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.jboss.logging.Logger;

import dev.manuelantunes.axonposts.application.tag.query.FindTagsByIdsQuery.FindTagsByIds;
import dev.manuelantunes.axonposts.application.tag.query.FindTagsByIdsQuery.TagsById;
import dev.manuelantunes.axonposts.application.tag.view.TagView;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import io.smallrye.graphql.api.federation.Resolver;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * O resolvedor de referência de {@code Tag}. Gêmeo do {@link PostEntityApi}, com uma diferença que vale
 * registrar.
 *
 * <h2>A tag só ganhou caminho próprio por causa da federação</h2>
 * Dentro deste schema não existe {@code Query.tag(id:)}: a tag é sempre alcançada a partir de um post,
 * pelo campo paginado {@code Post.tags}. Isso bastava enquanto o schema era um só. Com a federação, um
 * subgraph vizinho pode referenciar {@code Tag} pela chave sem nunca ter visto um post — e o roteador
 * volta aqui pedindo {@code _entities}, não {@code posts}.
 * <p>
 * É a diferença entre <i>ter id</i> e <i>ser entidade</i>: a chave só vale se houver como resolvê-la
 * isolada. Sem este método o {@code @Key} da {@code Tag} seria uma promessa que a composição aceita e o
 * runtime quebra.
 */
@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class TagEntityApi {

    private static final Logger log = Logger.getLogger(TagEntityApi.class);

    private final QueryGateway queryGateway;

    public TagEntityApi(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    @Resolver
    public Uni<List<TagView>> tag(@Name("id") List<String> id) {
        log.debugf("_entities: lote de %d tag(s) numa consulta", id.size());
        return Uni.createFrom()
                .completionStage(() -> queryGateway.query(new FindTagsByIds(id), TagsById.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(TagsById::byId)
                .map(byId -> id.stream().map(byId::get).toList());
    }
}
