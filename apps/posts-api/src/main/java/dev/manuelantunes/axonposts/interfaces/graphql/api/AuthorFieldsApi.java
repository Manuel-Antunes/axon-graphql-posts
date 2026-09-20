package dev.manuelantunes.axonposts.interfaces.graphql.api;

import java.util.List;
import java.util.Map;

import org.axonframework.messaging.queryhandling.gateway.QueryGateway;
import org.eclipse.microprofile.graphql.Description;
import org.eclipse.microprofile.graphql.GraphQLApi;
import org.eclipse.microprofile.graphql.Name;
import org.eclipse.microprofile.graphql.NonNull;
import org.eclipse.microprofile.graphql.Source;
import org.jboss.logging.Logger;

import dev.manuelantunes.axonposts.application.post.query.FindPostsByAuthorIdsQuery.FindPostsByAuthorIds;
import dev.manuelantunes.axonposts.application.post.query.FindPostsByAuthorIdsQuery.PostsByAuthor;
import dev.manuelantunes.axonposts.application.post.view.PostView;
import dev.manuelantunes.axonposts.application.user.view.AuthorView;
import dev.manuelantunes.axonposts.interfaces.graphql.error.TranslatesErrors;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.ConnectionArgs;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.Connections;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.PostConnection;
import dev.manuelantunes.axonposts.interfaces.graphql.relay.PostEdge;
import io.smallrye.mutiny.Uni;
import io.smallrye.mutiny.infrastructure.Infrastructure;
import jakarta.enterprise.context.ApplicationScoped;

@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class AuthorFieldsApi {
    private static final Logger log = Logger.getLogger(AuthorFieldsApi.class);

    private final QueryGateway queryGateway;

    public AuthorFieldsApi(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    @Name("posts")
    @NonNull
    @Description("This author's posts, newest first, as a Relay cursor connection")
    public Uni<List<PostConnection>> posts(
            @Source List<AuthorView> authors,
            @Name("first") @Description("How many posts to fetch; absent = 20") Integer first,
            @Name("after") @Description("Cursor of the last post already seen; absent = from the start") String after) {
        ConnectionArgs args = ConnectionArgs.of(PostQueryApi.CURSOR_TYPE, first, after);
        List<String> authorIds = authors.stream().map(AuthorView::id).distinct().toList();
        log.debugf("lote de posts por autor: %d autor(es) numa consulta", authorIds.size());

        return Uni.createFrom()
                .completionStage(() ->
                        queryGateway.query(new FindPostsByAuthorIds(authorIds), PostsByAuthor.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(PostsByAuthor::byAuthorId)
                .map(byAuthorId -> authors.stream()
                        .map(author -> connection(byAuthorId, author, args))
                        .toList());
    }

    private static PostConnection connection(Map<String, List<PostView>> byAuthorId,
                                             AuthorView author,
                                             ConnectionArgs args) {
        List<PostView> posts = byAuthorId.getOrDefault(author.id(), List.of());
        return Connections.slice(posts, args, PostEdge::new, PostConnection::new);
    }
}
