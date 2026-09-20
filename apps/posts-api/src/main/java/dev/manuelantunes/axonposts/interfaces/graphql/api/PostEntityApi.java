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
