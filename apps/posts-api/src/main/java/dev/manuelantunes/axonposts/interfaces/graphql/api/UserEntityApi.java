package dev.manuelantunes.axonposts.interfaces.graphql.api;

import java.util.List;
import java.util.Map;

import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.jboss.logging.Logger;

import dev.manuelantunes.axonposts.application.user.query.FindUsersByIdsQuery.FindUsersByIds;
import dev.manuelantunes.axonposts.application.user.query.FindUsersByIdsQuery.UsersById;
import dev.manuelantunes.axonposts.application.user.view.AuthorView;
import dev.manuelantunes.axonposts.application.user.view.ReaderView;
import dev.manuelantunes.axonposts.application.user.view.UserView;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import io.smallrye.graphql.api.federation.Resolver;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class UserEntityApi {
    private static final Logger log = Logger.getLogger(UserEntityApi.class);

    private final QueryGateway queryGateway;

    public UserEntityApi(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    @Resolver
    public Uni<List<UserView>> user(@Name("id") List<String> id) {
        return byId(id).map(byId -> id.stream().map(byId::get).toList());
    }

    @Resolver
    public Uni<List<AuthorView>> author(@Name("id") List<String> id) {
        return byId(id).map(byId -> id.stream().map(userId -> as(byId, userId, AuthorView.class)).toList());
    }

    @Resolver
    public Uni<List<ReaderView>> reader(@Name("id") List<String> id) {
        return byId(id).map(byId -> id.stream().map(userId -> as(byId, userId, ReaderView.class)).toList());
    }

    private Uni<Map<String, UserView>> byId(List<String> ids) {
        log.debugf("_entities: lote de %d usuário(s) numa consulta", ids.size());
        return Uni.createFrom()
                .completionStage(() -> queryGateway.query(new FindUsersByIds(ids), UsersById.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(UsersById::byId);
    }

    private static <T extends UserView> T as(Map<String, UserView> byId, String userId, Class<T> type) {
        UserView user = byId.get(userId);
        return type.isInstance(user) ? type.cast(user) : null;
    }
}
