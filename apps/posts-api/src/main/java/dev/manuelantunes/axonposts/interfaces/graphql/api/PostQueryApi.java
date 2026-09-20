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

@GraphQLApi
@ApplicationScoped
@TranslatesErrors
public class PostQueryApi {
    static final String CURSOR_TYPE = "post";

    private final QueryGateway queryGateway;

    public PostQueryApi(QueryGateway queryGateway) {
        this.queryGateway = queryGateway;
    }

    @Query("post")
    @Description("A Post by id; null if it does not exist")
    public Uni<PostView> post(@Name("id") @Id @NonNull String id) {
        return Uni.createFrom()
                .completionStage(() -> queryGateway.query(new FindPost(id), PostView.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool());
    }

    @Query("posts")
    @NonNull
    @Description("Posts in creation order, as a Relay cursor connection")
    public Uni<PostConnection> posts(
            @Name("first") @Description("How many posts to fetch; absent = 20") Integer first,
            @Name("after") @Description("Cursor of the last post already seen; absent = from the start") String after) {
        ConnectionArgs args = ConnectionArgs.of(CURSOR_TYPE, first, after);

        return Uni.createFrom()
                .completionStage(() -> queryGateway.query(
                        new FindAllPosts(args.offset(), args.limit()), PostPage.class))
                .runSubscriptionOn(Infrastructure.getDefaultWorkerPool())
                .map(page -> Connections.page(
                        page.items(), page.hasNext(), args, PostEdge::new, PostConnection::new));
    }
}
