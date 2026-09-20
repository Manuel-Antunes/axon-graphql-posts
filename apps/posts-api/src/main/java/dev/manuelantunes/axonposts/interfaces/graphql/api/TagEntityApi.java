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
